package com.coolpilot.insight.system.controller.dict;

import com.coolpilot.insight.common.security.RequirePermission;
import com.coolpilot.insight.common.security.RequireRole;
import com.coolpilot.insight.common.web.Result;
import com.coolpilot.insight.system.application.dict.DictService;
import com.coolpilot.insight.system.domain.dict.DictItem;
import com.coolpilot.insight.system.domain.dict.DictType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 字典接口。读接口登录即可（前端下拉框要用），写接口需字典管理权限。 */
@RestController
@RequestMapping("/api/v1/system/dicts")
public class DictController {

    private final DictService dictService;

    public DictController(DictService dictService) {
        this.dictService = dictService;
    }

    @RequireRole(role = 0)
    @GetMapping
    public Result<List<DictType>> listTypes() {
        return Result.ok(dictService.listTypes());
    }

    @RequireRole(role = 0)
    @GetMapping("/{typeCode}/items")
    public Result<List<DictItem>> listItems(@PathVariable String typeCode) {
        return Result.ok(dictService.listItems(typeCode));
    }

    @RequireRole(role = 0)
    @RequirePermission("system:dict:manage")
    @PostMapping
    public Result<DictType> createType(@RequestBody Map<String, String> body) {
        return Result.ok(dictService.createType(body.get("typeCode"), body.get("typeName"),
                body.get("remark")));
    }

    @RequireRole(role = 0)
    @RequirePermission("system:dict:manage")
    @PostMapping("/{typeCode}/items")
    public Result<DictItem> addItem(@PathVariable String typeCode,
                                    @RequestBody Map<String, Object> body) {
        Integer sort = body.get("sort") == null ? null : Integer.valueOf(body.get("sort").toString());
        return Result.ok(dictService.addItem(typeCode, (String) body.get("itemValue"),
                (String) body.get("itemLabel"), sort));
    }

    @RequireRole(role = 0)
    @RequirePermission("system:dict:manage")
    @DeleteMapping("/{typeCode}")
    public Result<String> deleteType(@PathVariable String typeCode) {
        dictService.deleteType(typeCode);
        return Result.ok("已删除");
    }
}
