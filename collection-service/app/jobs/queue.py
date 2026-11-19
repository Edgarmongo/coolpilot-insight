"""作业队列（SOURCE-06 · C3）。

**为什么必须排队**：Chrome 只有一个，共享 ``chrome-data`` 卷（ADR-007）。
两个作业同时启动浏览器，第二个会撞上 profile 锁，表现为"采集随机失败"——
这种失败极难归因，所以宁可排队也不并发。

设计取舍：

* 只有 **一个 worker 线程**。不是"懒得做并发"，是并发在这里没有正确解；
* 排队期间状态保持 ``ACCEPTED``，**不新增 QUEUED 状态**——
  作业状态词表已经被 Java 侧引用，加值就要同步改两侧；
* ``queuePosition`` 让调用方能解释"为什么还没开始"，而不是看着 ACCEPTED 干等；
* 取消对排队中的作业是**立即生效**的（直接摘除）；对已经在跑的只能"结束后丢弃"
  （见 ``cancel`` 的说明），因为强行杀掉 Chrome 会留下 profile 锁。
"""

from __future__ import annotations

import threading
import time
import queue as _queue
from dataclasses import dataclass, field
from typing import Any, Callable

ACCEPTED = "ACCEPTED"
RUNNING = "RUNNING"
SUCCEEDED = "SUCCEEDED"
PARTIAL = "PARTIAL"
FAILED = "FAILED"
CANCELLED = "CANCELLED"

TERMINAL = {SUCCEEDED, PARTIAL, FAILED, CANCELLED}


@dataclass
class Job:
    """一个采集作业。字段与 ``GET /v1/jobs/{id}`` 的响应一一对应。"""

    job_id: str
    connector_code: str
    capability_code: str
    access_mode: str
    external_content_id: str
    max_items: int
    params: dict[str, Any] = field(default_factory=dict)

    status: str = ACCEPTED
    collected: int = 0
    total: int = 0
    items: list[dict[str, Any]] = field(default_factory=list)
    error_code: str | None = None
    error_message: str | None = None
    #: 采集器给出的结束原因（NO_MORE_DATA / NO_RESPONSE / PREFLIGHT_FAILED …）。
    #: 它决定"0 条"是"已全部采过"还是"根本没抓到"。
    finished_reason: str | None = None
    #: 实际收到过几页数据。0 表示"一个数据包都没拿到"——那是连不上，不是采完了。
    pages: int = 0

    #: 本次去重记忆来源与规模（U1 A-API）。用来解释"为什么 collected=0"。
    dedup_source: str | None = None
    dedup_seen: int = 0

    #: 本次去重记忆来源与规模（U1 A-API）。用来解释"为什么 collected=0"。
    dedup_source: str | None = None
    dedup_seen: int = 0

    seq: int = 0
    created_at: float = field(default_factory=time.time)
    started_at: float | None = None
    finished_at: float | None = None
    updated_at: float = field(default_factory=time.time)

    #: 排队中被取消时立即生效；运行中被取消则置位，由执行体在结束时丢弃结果。
    cancel_requested: bool = False

    #: 采集器的过程输出。**必须留**：0 条结果时，唯一能解释
    #: "是没抓到数据包、还是平台确实没评论"的就是这几行。
    logs: list[str] = field(default_factory=list)

    def log(self, message: str) -> None:
        # 采集器的进度输出带前导换行与回车（滚动刷新），原样塞进 JSON 会变成
        # 非法控制字符，调用方解析状态时直接报错。这里统一净化：
        # 回车/换行折叠为空格，其余控制字符丢弃。
        text = str(message).replace(chr(13), " ").replace(chr(10), " ")
        text = "".join(ch for ch in text if ch >= " " or ch == chr(9))
        self.logs.append(text.strip()[:300])
        if len(self.logs) > 200:
            del self.logs[:100]

    def to_status(self, queue_position: int) -> dict[str, Any]:
        return {
            "jobId": self.job_id,
            "status": self.status,
            "collected": self.collected,
            "total": self.total,
            "errorCode": self.error_code,
            "errorMessage": self.error_message,
            "finishedReason": self.finished_reason,
            "pages": self.pages,
            "dedupSource": self.dedup_source,
            "dedupSeen": self.dedup_seen,
            "dedupSource": self.dedup_source,
            "dedupSeen": self.dedup_seen,
            "queuePosition": queue_position,
            "startedAt": self.started_at,
            "finishedAt": self.finished_at,
            # 尾部日志：前端/排障只需要最后几行，全量留给进程内查看
            "logTail": self.logs[-20:],
        }


class QueueFull(Exception):
    """待处理作业过多。宁可 429，也不让内存无限增长。"""


class JobQueue:
    """单 worker 作业队列。

    :param runner: ``runner(job) -> None``，负责真正执行并写回 job 的
        状态/条目/错误。抛异常即视为作业失败。
    :param max_pending: 允许排队（ACCEPTED）的上限。
    """

    def __init__(self, runner: Callable[[Job], None], max_pending: int = 100) -> None:
        self._runner = runner
        self._max_pending = max_pending
        self._jobs: dict[str, Job] = {}
        self._pending: "_queue.Queue[str]" = _queue.Queue()
        self._lock = threading.Lock()
        self._seq = 0
        self._worker = threading.Thread(target=self._loop, name="collect-job-worker", daemon=True)
        self._worker.start()

    # ------------------------------------------------------------------
    # 提交
    # ------------------------------------------------------------------
    def submit(self, job: Job) -> tuple[Job, bool]:
        """入队。返回 ``(job, created)``——``created=False`` 表示幂等命中。

        同一 ``jobId`` 重复提交返回既有作业且**不重复入队**：
        上游重试是常态，重复采集一遍既慢又可能触发平台风控。
        """
        with self._lock:
            existing = self._jobs.get(job.job_id)
            if existing is not None:
                return existing, False

            pending = sum(1 for j in self._jobs.values() if j.status == ACCEPTED)
            if pending >= self._max_pending:
                raise QueueFull(f"待执行作业已达上限 {self._max_pending}，请稍后重试")

            self._seq += 1
            job.seq = self._seq
            self._jobs[job.job_id] = job
            self._pending.put(job.job_id)
            return job, True

    # ------------------------------------------------------------------
    # 查询
    # ------------------------------------------------------------------
    def get(self, job_id: str) -> Job | None:
        with self._lock:
            return self._jobs.get(job_id)

    def queue_position(self, job: Job) -> int:
        """前面还有几个排队中的作业；已在执行或已结束返回 0。"""
        if job.status != ACCEPTED:
            return 0
        with self._lock:
            return sum(1 for j in self._jobs.values()
                       if j.status == ACCEPTED and j.seq < job.seq)

    # ------------------------------------------------------------------
    # 取消
    # ------------------------------------------------------------------
    def cancel(self, job_id: str) -> Job | None:
        """尽力取消。

        * 排队中：立即置 ``CANCELLED``，worker 取到时会直接跳过；
        * 执行中：只置 ``cancel_requested``，等执行体在安全点检查后丢弃结果。
          **不杀 Chrome**：强杀会留下 profile 锁，下一次启动直接失败，
          代价远高于"晚几秒停"。
        * 已结束：原样返回，不改状态（不把"已完成"改写成"已取消"）。
        """
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None or job.status in TERMINAL:
                return job
            if job.status == ACCEPTED:
                job.status = CANCELLED
                job.finished_at = time.time()
                job.updated_at = job.finished_at
            else:
                job.cancel_requested = True
            return job

    # ------------------------------------------------------------------
    # worker
    # ------------------------------------------------------------------
    def _loop(self) -> None:
        while True:
            job_id = self._pending.get()
            job = self.get(job_id)
            if job is None or job.status == CANCELLED:
                continue
            with self._lock:
                job.status = RUNNING
                job.started_at = time.time()
                job.updated_at = job.started_at
            try:
                self._runner(job)
            except Exception as e:  # 单作业失败绝不能拖垮 worker
                with self._lock:
                    job.status = FAILED
                    job.error_code = getattr(e, "code", "INTERNAL_ERROR")
                    job.error_message = str(e)[:500]
            finally:
                with self._lock:
                    job.finished_at = time.time()
                    job.updated_at = job.finished_at
