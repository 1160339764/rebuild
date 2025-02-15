/*
Copyright (c) REBUILD <https://getrebuild.com/> and/or its owners. All rights reserved.

rebuild is dual-licensed under commercial and open source licenses (GPLv3).
See LICENSE and COMMERCIAL in the project root for license information.
*/

package com.rebuild.core.service.trigger.impl;

import cn.devezhao.bizz.privileges.impl.BizzPermission;
import cn.devezhao.commons.CalendarUtils;
import cn.devezhao.commons.ObjectUtils;
import cn.devezhao.persist4j.Field;
import cn.devezhao.persist4j.Record;
import cn.devezhao.persist4j.dialect.FieldType;
import cn.devezhao.persist4j.engine.ID;
import cn.devezhao.persist4j.engine.StandardRecord;
import cn.devezhao.persist4j.metadata.MissingMetaExcetion;
import cn.devezhao.persist4j.record.RecordVisitor;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.rebuild.core.Application;
import com.rebuild.core.configuration.general.AutoFillinManager;
import com.rebuild.core.metadata.EntityHelper;
import com.rebuild.core.metadata.MetadataHelper;
import com.rebuild.core.metadata.easymeta.DisplayType;
import com.rebuild.core.metadata.easymeta.EasyDateTime;
import com.rebuild.core.metadata.easymeta.EasyField;
import com.rebuild.core.metadata.easymeta.EasyMetaFactory;
import com.rebuild.core.privileges.PrivilegesGuardContextHolder;
import com.rebuild.core.privileges.UserService;
import com.rebuild.core.service.ServiceSpec;
import com.rebuild.core.service.general.GeneralEntityServiceContextHolder;
import com.rebuild.core.service.general.OperatingContext;
import com.rebuild.core.service.trigger.ActionContext;
import com.rebuild.core.service.trigger.ActionType;
import com.rebuild.core.service.trigger.TriggerException;
import com.rebuild.core.service.trigger.aviator.AviatorUtils;
import com.rebuild.core.support.general.ContentWithFieldVars;
import com.rebuild.core.support.general.N2NReferenceSupport;
import com.rebuild.utils.CommonsUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;

import java.util.*;

/**
 * 字段更新
 *
 * @author devezhao
 * @since 2020/2/7
 *
 * @see AutoFillinManager
 */
@Slf4j
public class FieldWriteback extends FieldAggregation {

    public static final String WB_ONE2ONE = "one2one";

    private static final String DATE_EXPR = "#";
    private static final String CODE_PREFIX = "{{{{";  // ends with }}}}

    private Set<ID> targetRecordIds;
    private Record targetRecordData;

    public FieldWriteback(ActionContext context) {
        super(context);
    }

    @Override
    public ActionType getType() {
        return ActionType.FIELDWRITEBACK;
    }

    @Override
    public void execute(OperatingContext operatingContext) throws TriggerException {
        final List<ID> tschain = checkTriggerChain();
        if (tschain == null) return;

        this.prepare(operatingContext);

        if (targetRecordIds.isEmpty()) {
            return;
        }
        if (targetRecordData.getAvailableFields().isEmpty()) {
            log.warn("No data of target record available");
            return;
        }

        final ServiceSpec targetService = MetadataHelper.isBusinessEntity(targetEntity)
                ? Application.getEntityService(targetEntity.getEntityCode())
                : Application.getService(targetEntity.getEntityCode());

        boolean tschainAdded = false;
        for (ID targetRecordId : targetRecordIds) {
            if (allowNoPermissionUpdate) {
                PrivilegesGuardContextHolder.setSkipGuard(targetRecordId);
            }
            // 如果当前用户对目标记录无修改权限
            else if (!Application.getPrivilegesManager().allow(operatingContext.getOperator(), targetRecordId, BizzPermission.UPDATE)) {
                log.warn("No permission to update record of target : {}", targetRecordId);
                continue;
            }

            // 会关联触发下一触发器
            if (!tschainAdded) {
                tschain.add(context.getConfigId());
                tschainAdded = true;
                TRIGGER_CHAIN_DEPTH.set(tschain);
            }

            Record targetRecord = targetRecordData.clone();
            targetRecord.setID(targetEntity.getPrimaryField().getName(), targetRecordId);

            GeneralEntityServiceContextHolder.setRepeatedCheckMode(GeneralEntityServiceContextHolder.RCM_CHECK_MAIN);
            try {
                targetService.createOrUpdate(targetRecord);  // Only create
            } finally {
                PrivilegesGuardContextHolder.getSkipGuardOnce();
                GeneralEntityServiceContextHolder.getRepeatedCheckModeOnce();
            }
        }
    }

    @Override
    public void prepare(OperatingContext operatingContext) throws TriggerException {
        if (targetRecordIds != null) return;

        // FIELD.ENTITY
        String[] targetFieldEntity = ((JSONObject) context.getActionContent()).getString("targetEntity").split("\\.");
        sourceEntity = context.getSourceEntity();
        targetEntity = MetadataHelper.getEntity(targetFieldEntity[1]);

        boolean isOne2One = ((JSONObject) context.getActionContent()).getBooleanValue(WB_ONE2ONE);

        targetRecordIds = new HashSet<>();

        if (SOURCE_SELF.equalsIgnoreCase(targetFieldEntity[0])) {
            // 自己
            targetRecordIds.add(context.getSourceRecord());

        } else if (isOne2One) {
            // 只会存在一个记录
            Record afterRecord = operatingContext.getAfterRecord();
            ID referenceId = afterRecord == null ? null : afterRecord.getID(targetFieldEntity[0]);
            if (referenceId == null) return;

            targetRecordIds.add(referenceId);

        } else {
            String sql = String.format("select %s from %s where %s = ?",
                    targetEntity.getPrimaryField().getName(), targetFieldEntity[1], targetFieldEntity[0]);
            Object[][] array = Application.createQueryNoFilter(sql)
                    .setParameter(1, operatingContext.getAnyRecord().getPrimary())
                    .array();

            for (Object[] o : array) {
                targetRecordIds.add((ID) o[0]);
            }

            if (targetRecordIds.isEmpty()) {
                log.warn("No target record(s) found");
                return;
            }
        }

        targetRecordData = buildTargetRecordData();
    }

    private Record buildTargetRecordData() {
        final Record targetRecord = EntityHelper.forNew(targetEntity.getEntityCode(), UserService.SYSTEM_USER, false);
        final JSONArray items = ((JSONObject) context.getActionContent()).getJSONArray("items");

        final Set<String> fieldVars = new HashSet<>();
        for (Object o : items) {
            JSONObject item = (JSONObject) o;
            String sourceField = item.getString("sourceField");
            String updateMode = item.getString("updateMode");
            // fix: v2.2
            if (updateMode == null) {
                updateMode = sourceField.contains(DATE_EXPR) ? "FORMULA" : "FIELD";
            }

            if ("FIELD".equalsIgnoreCase(updateMode)) {
                fieldVars.add(sourceField);
            } else if ("FORMULA".equalsIgnoreCase(updateMode)) {
                if (sourceField.contains(DATE_EXPR) && !sourceField.startsWith(CODE_PREFIX)) {
                    fieldVars.add(sourceField.split(DATE_EXPR)[0]);
                } else {
                    Set<String> matchsVars = ContentWithFieldVars.matchsVars(sourceField);
                    for (String field : matchsVars) {
                        if (MetadataHelper.getLastJoinField(sourceEntity, field) == null) {
                            throw new MissingMetaExcetion(field, sourceEntity.getName());
                        }
                        fieldVars.add(field);
                    }
                }
            }
        }

        // 变量值
        Record useSourceData = null;
        if (!fieldVars.isEmpty()) {
            String sql = String.format("select %s from %s where %s = '%s'",
                    StringUtils.join(fieldVars, ","), sourceEntity.getName(),
                    sourceEntity.getPrimaryField().getName(), context.getSourceRecord());
            useSourceData = Application.createQueryNoFilter(sql).record();
        }

        for (Object o : items) {
            JSONObject item = (JSONObject) o;
            String targetField = item.getString("targetField");
            if (!MetadataHelper.checkAndWarnField(targetEntity, targetField)) {
                continue;
            }

            EasyField targetFieldEasy = EasyMetaFactory.valueOf(targetEntity.getField(targetField));

            String updateMode = item.getString("updateMode");
            String sourceAny = item.getString("sourceField");

            // 置空
            if ("VNULL".equalsIgnoreCase(updateMode)) {
                targetRecord.setNull(targetField);
            }

            // 固定值
            else if ("VFIXED".equalsIgnoreCase(updateMode)) {
                RecordVisitor.setValueByLiteral(targetField, sourceAny, targetRecord);
            }

            // 字段
            else if ("FIELD".equalsIgnoreCase(updateMode)) {
                Field sourceFieldMeta = MetadataHelper.getLastJoinField(sourceEntity, sourceAny);
                if (sourceFieldMeta == null) continue;

                Object value = Objects.requireNonNull(useSourceData).getObjectValue(sourceAny);

                if (value != null) {
                    if (targetFieldEasy.getDisplayType() == DisplayType.N2NREFERENCE) {
                        value = N2NReferenceSupport.items(sourceFieldMeta, context.getSourceRecord());
                    }

                    Object newValue = EasyMetaFactory.valueOf(sourceFieldMeta)
                            .convertCompatibleValue(value, targetFieldEasy);
                    targetRecord.setObjectValue(targetField, newValue);
                }
            }

            // 公式
            else if ("FORMULA".equalsIgnoreCase(updateMode)) {
                if (useSourceData == null) {
                    log.warn("[useSourceData] is null, Set to empty");
                    useSourceData = new StandardRecord(sourceEntity, null);
                }

                // 高级公式代码
                final boolean useCode = sourceAny.startsWith(CODE_PREFIX);

                // 日期兼容 fix: v2.2
                if (sourceAny.contains(DATE_EXPR) && !useCode) {
                    String fieldName = sourceAny.split(DATE_EXPR)[0];
                    Field sourceField2 = MetadataHelper.getLastJoinField(sourceEntity, fieldName);
                    if (sourceField2 == null) continue;

                    Object value = useSourceData.getObjectValue(fieldName);
                    Object newValue = value == null ? null : ((EasyDateTime) EasyMetaFactory.valueOf(sourceField2))
                            .convertCompatibleValue(value, targetFieldEasy, sourceAny);
                    if (newValue != null) {
                        targetRecord.setObjectValue(targetField, newValue);
                    }
                }

                // 公式
                // @see AggregationEvaluator#evalFormula
                else {
                    String clearFormual = useCode
                            ? sourceAny.substring(4, sourceAny.length() - 4)
                            : sourceAny
                                .replace("×", "*")
                                .replace("÷", "/")
                                .replace("`", "\"");  // compatible: v2.4

                    Map<String, Object> envMap = new HashMap<>();

                    for (String fieldName : fieldVars) {
                        String replace = "{" + fieldName + "}";
                        String replaceWhitQuote = "\"" + replace + "\"";

                        if (clearFormual.contains(replaceWhitQuote)) {
                            clearFormual = clearFormual.replace(replaceWhitQuote, fieldName);
                        } else if (clearFormual.contains(replace)) {
                            clearFormual = clearFormual.replace(replace, fieldName);
                        } else {
                            continue;
                        }

                        Object value = useSourceData.getObjectValue(fieldName);

                        if (value instanceof Date) {
                            value = CalendarUtils.getUTCDateTimeFormat().format(value);
                        } else if (value == null) {
                            // 数字字段置 `0`
                            Field isNumberField = MetadataHelper.getLastJoinField(sourceEntity, fieldName);
                            if (isNumberField != null
                                    && (isNumberField.getType() == FieldType.LONG || isNumberField.getType() == FieldType.DECIMAL)) {
                                value = "0";
                            } else {
                                value = StringUtils.EMPTY;
                            }
                        } else {
                            value = value.toString();
                        }

                        envMap.put(fieldName, value);
                    }

                    Object newValue = AviatorUtils.eval(clearFormual, envMap, false);

                    if (newValue != null) {
                        DisplayType dt = targetFieldEasy.getDisplayType();
                        if (dt == DisplayType.NUMBER) {
                            targetRecord.setLong(targetField, CommonsUtils.toLongHalfUp(newValue));
                        } else if (dt == DisplayType.DECIMAL) {
                            targetRecord.setDouble(targetField, ObjectUtils.toDouble(newValue));
                        } else if (dt == DisplayType.DATE || dt == DisplayType.DATETIME) {
                            targetRecord.setDate(targetField, (Date) newValue);
                        } else {
                            newValue = checkoutFieldValue(newValue, targetFieldEasy);
                            if (newValue != null) {
                                targetRecord.setObjectValue(targetField, newValue);
                            }
                        }
                    }
                }
            }
        }

        return targetRecord;
    }

    /**
     * @see DisplayType
     * @see com.rebuild.core.metadata.EntityRecordCreator
     */
    private Object checkoutFieldValue(Object value, EasyField field) {

        DisplayType dt = field.getDisplayType();
        Object newValue = null;

        if (dt == DisplayType.PICKLIST || dt == DisplayType.CLASSIFICATION
                || dt == DisplayType.REFERENCE || dt == DisplayType.ANYREFERENCE) {

            ID id = ID.isId(value) ? ID.valueOf(value.toString()) : null;
            if (id != null) {
                int idCode = id.getEntityCode();
                if (dt == DisplayType.PICKLIST) {
                    if (idCode == EntityHelper.PickList) newValue = id;
                } else if (dt == DisplayType.CLASSIFICATION) {
                    if (idCode == EntityHelper.ClassificationData) newValue = id;
                } else if (dt == DisplayType.REFERENCE) {
                    if (field.getRawMeta().getReferenceEntity().getEntityCode() == idCode) newValue = id;
                } else {
                    newValue = id;
                }
            }

        } else if (dt == DisplayType.N2NREFERENCE) {

            String[] ids = value.toString().split(",");
            List<String> idsList = new ArrayList<>();
            for (String id : ids) {
                if (ID.isId(id)) idsList.add(id);
            }
            if (ids.length == idsList.size()) newValue = value.toString();

        } else if (dt == DisplayType.BOOL) {

            if (value instanceof Boolean) {
                newValue = value;
            } else {
                newValue = BooleanUtils.toBooleanObject(value.toString());
            }

        } else if (dt == DisplayType.MULTISELECT || dt == DisplayType.STATE) {

            if (value instanceof Integer || value instanceof Long) {
                newValue = value;
            }

        } else {
            // TODO 验证字段格式
            newValue = value.toString();
        }

        if (newValue == null) {
            log.warn("Value `{}` cannot be convert to field (value) : {}", value, field.getRawMeta());
        }
        return newValue;
    }
}
