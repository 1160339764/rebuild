/*
rebuild - Building your system freely.
Copyright (C) 2018 devezhao <zhaofang123@gmail.com>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package com.rebuild.server.service.base.obs;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.rebuild.server.Application;
import com.rebuild.server.metadata.EntityHelper;
import com.rebuild.server.metadata.entityhub.DisplayType;
import com.rebuild.server.metadata.entityhub.EasyMeta;
import com.rebuild.server.service.OperateContext;
import com.rebuild.server.service.OperateObserver;
import com.rebuild.utils.JSONUtils;

import cn.devezhao.persist4j.Entity;
import cn.devezhao.persist4j.Field;
import cn.devezhao.persist4j.Record;
import cn.devezhao.persist4j.engine.ID;

/**
 * 更新附件
 * 
 * @author devezhao
 * @since 12/25/2018
 */
public class AttchementAwareObserver extends OperateObserver {
	
	@Override
	public void onCreate(OperateContext context) {
		Record record = context.getAfterRecord();
		Field[] attFields = getAttchementFields(record.getEntity());
		if (attFields.length == 0) {
			return;
		}
		
		List<Record> createWill = new ArrayList<>();
		for (Field field : attFields) {
			if (record.hasValue(field.getName())) {
				JSONArray filesJson = parseFilesJson(record.getString(field.getName()));
				for (Object file : filesJson) {
					Record att = createAttachment(context, field, (String) file);
					createWill.add(att);
				}
			}
		}
		if (createWill.isEmpty()) {
			return;
		}
		
		Application.getCommonService().createOrUpdate(
				createWill.toArray(new Record[createWill.size()]));
	}
	
	@Override
	public void onUpdate(OperateContext context) {
		Record record = context.getAfterRecord();
		Field[] attFields = getAttchementFields(record.getEntity());
		if (attFields.length == 0) {
			return;
		}
		
		Record before = context.getBeforeRecord();
		
		List<Record> createWill = new ArrayList<>();
		List<ID> deleteWill = new ArrayList<>();
		for (Field field : attFields) {
			String fieldName = field.getName();
			if (record.hasValue(fieldName)) {
				JSONArray beforeFiles = parseFilesJson(before.getString(fieldName));  // 修改前
				JSONArray afterFiles = parseFilesJson(record.getString(fieldName));	  // 修改后
				
				for (Iterator<Object> iter = afterFiles.iterator(); iter.hasNext(); ) {
					Object a = iter.next();
					if (beforeFiles.contains(a)) {
						beforeFiles.remove(a);
						iter.remove();
					}
				}
				if (LOG.isDebugEnabled()) {
					LOG.debug("删除 ... " + beforeFiles);
					LOG.debug("增加 ... " + afterFiles);
				}
				
				for (Object o : beforeFiles) {
					Object[] delete = Application.createQueryNoFilter(
							"select attachmentId from Attachment where belongEntity = ? and belongField = ? and filePath = ?")
							.setParameter(1, field.getOwnEntity().getEntityCode())
							.setParameter(2, fieldName)
							.setParameter(3, o)
							.unique();
					if (delete != null) {
						deleteWill.add((ID) delete[0]);
					}
				}
				
				for (Object o : afterFiles) {
					Record att = createAttachment(context, field, (String) o);
					createWill.add(att);
				}
			}
		}
		if (createWill.isEmpty() && deleteWill.isEmpty()) {
			return;
		}
		
		Application.getCommonService().createOrUpdate(
				createWill.toArray(new Record[createWill.size()]), deleteWill.toArray(new ID[deleteWill.size()]));
	}
	
	@Override
	public void onDelete(OperateContext context) {
		Record record = context.getBeforeRecord();
		Field[] attFields = getAttchementFields(record.getEntity());
		if (attFields.length == 0) {
			return;
		}
		
		Object[][] array = Application.createQueryNoFilter(
				"select attachmentId from Attachment where relatedRecord = ?")
				.setParameter(1, record.getPrimary().toLiteral())
				.array();
		List<ID> deleteWill = new ArrayList<>();
		for (Object[] o : array) {
			deleteWill.add((ID) o[0]);
		}
		if (deleteWill.isEmpty()) {
			return;
		}
		
		Application.getCommonService().delete(
				deleteWill.toArray(new ID[deleteWill.size()]));
	}
	
	/**
	 * @param entity
	 * @return
	 */
	private Field[] getAttchementFields(Entity entity) {
		List<Field> fields = new ArrayList<>();
		for (Field field : entity.getFields()) {
			DisplayType dt = EasyMeta.valueOf(field).getDisplayType();
			if (dt == DisplayType.FILE || dt == DisplayType.IMAGE) {
				fields.add(field);
			}
		}
		return fields.toArray(new Field[fields.size()]);
	}
	
	/**
	 * @param files
	 * @return
	 */
	private JSONArray parseFilesJson(String files) {
		if (StringUtils.isBlank(files)) {
			return JSONUtils.EMPTY_ARRAY;
		}
		return JSON.parseArray(files);
	}
	
	/**
	 * @param context
	 * @param field
	 * @param filePath
	 * @return
	 */
	private Record createAttachment(OperateContext context, Field field, String filePath) {
		Record record = context.getAfterRecord();
		Record att = EntityHelper.forNew(EntityHelper.Attachment, context.getOperator());
		att.setInt("belongEntity", record.getEntity().getEntityCode());
		att.setString("belongField", field.getName());
		att.setString("relatedRecord", record.getPrimary().toLiteral());
		att.setString("filePath", filePath);
		String ext = FilenameUtils.getExtension(filePath);
		if (StringUtils.isNotBlank(ext)) {
			att.setString("fileType", ext);
		}
		return att;
	}
}