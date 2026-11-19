package com.coolpilot.insight.system.application.dict;

import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.system.application.AuditService;
import com.coolpilot.insight.system.domain.dict.DictItem;
import com.coolpilot.insight.system.domain.dict.DictType;
import com.coolpilot.insight.system.infrastructure.dict.DictMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 字典管理。
 *
 * <p>边界：字典只承载「运营可调整、后端不做分支判断」的值。
 * 参与流程判断的固定枚举（任务状态、复核结果等）仍用 Java enum——
 * 放进字典会导致"数据库改一个值就让代码逻辑失效"。
 */
@Service
public class DictService {

    private final DictMapper dictMapper;
    private final AuditService auditService;

    public DictService(DictMapper dictMapper, AuditService auditService) {
        this.dictMapper = dictMapper;
        this.auditService = auditService;
    }

    public List<DictType> listTypes() {
        return dictMapper.selectTypes();
    }

    public List<DictItem> listItems(String typeCode) {
        DictType t = requireType(typeCode);
        return dictMapper.selectItems(t.getId());
    }

    public DictType createType(String code, String name, String remark) {
        String c = code == null ? "" : code.trim().toLowerCase();
        if (!c.matches("[a-z][a-z0-9_]{1,63}")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "类型编码需为 2-64 位小写字母/数字/下划线");
        }
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "类型名称不能为空");
        }
        if (dictMapper.selectTypeByCode(c) != null) {
            throw new BizException(ErrorCode.DUPLICATE, "字典类型已存在: " + c);
        }
        DictType t = new DictType();
        t.setTypeCode(c);
        t.setTypeName(name.trim());
        t.setRemark(remark);
        dictMapper.insertType(t);
        auditService.audit("DICT_TYPE_CREATE", "SYS_DICT_TYPE", t.getId(), "{\"code\":\"" + c + "\"}");
        return dictMapper.selectTypeById(t.getId());
    }

    public DictItem addItem(String typeCode, String value, String label, Integer sort) {
        DictType t = requireType(typeCode);
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "字典值不能为空");
        }
        if (label == null || label.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "字典标签不能为空");
        }
        if (dictMapper.selectItem(t.getId(), v) != null) {
            throw new BizException(ErrorCode.DUPLICATE, "该类型下已存在值: " + v);
        }
        DictItem item = new DictItem();
        item.setTypeId(t.getId());
        item.setItemValue(v);
        item.setItemLabel(label.trim());
        item.setSort(sort == null ? 0 : sort);
        dictMapper.insertItem(item);
        return item;
    }

    public void disableItem(Long itemId) {
        dictMapper.updateItemStatus(itemId, "DISABLED");
    }

    /**
     * 删除字典类型。
     *
     * <p>有字典项时不允许直接删——会连带删掉项，而业务数据可能还存着这些值。
     */
    public void deleteType(String typeCode) {
        DictType t = requireType(typeCode);
        if (!dictMapper.selectItems(t.getId()).isEmpty()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "该类型下仍有字典项，请先清理");
        }
        dictMapper.deleteType(t.getId());
        auditService.audit("DICT_TYPE_DELETE", "SYS_DICT_TYPE", t.getId(), "{\"code\":\"" + t.getTypeCode() + "\"}");
    }

    private DictType requireType(String code) {
        DictType t = dictMapper.selectTypeByCode(code);
        if (t == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "字典类型不存在: " + code);
        }
        return t;
    }
}
