package com.coolpilot.insight.system.dict;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.system.application.dict.DictService;
import com.coolpilot.insight.system.domain.dict.DictItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 字典管理（Batch 2）。重点：唯一性、有项时不可删类型。 */
class DictServiceTest extends AbstractIntegrationTest {

    @Autowired
    private DictService dictService;

    @Test
    @DisplayName("种子字典类型与项存在，且按 sort 排序")
    void seedsPresent() {
        assertThat(dictService.listTypes()).extracting(t -> t.getTypeCode())
                .contains("industry", "source_channel");

        List<DictItem> items = dictService.listItems("industry");
        assertThat(items).isNotEmpty();
        assertThat(items.get(0).getSort()).isLessThanOrEqualTo(items.get(items.size() - 1).getSort());
    }

    @Test
    @DisplayName("重复类型编码被拒")
    void duplicateTypeRejected() {
        assertThatThrownBy(() -> dictService.createType("industry", "重复", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE);
    }

    @Test
    @DisplayName("类型编码格式非法被拒")
    void invalidTypeCodeRejected() {
        assertThatThrownBy(() -> dictService.createType("Bad Code", "x", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("同类型下重复字典值被拒")
    void duplicateItemRejected() {
        dictService.createType("test_dict", "测试字典", null);
        dictService.addItem("test_dict", "a", "选项A", 1);

        assertThatThrownBy(() -> dictService.addItem("test_dict", "a", "重复A", 2))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE);
    }

    @Test
    @DisplayName("类型下仍有项时不可删除")
    void cannotDeleteTypeWithItems() {
        dictService.createType("test_dict2", "测试字典2", null);
        dictService.addItem("test_dict2", "x", "X", 1);

        assertThatThrownBy(() -> dictService.deleteType("test_dict2"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("不存在的类型返回 404 业务码")
    void unknownType() {
        assertThatThrownBy(() -> dictService.listItems("no_such"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
