/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package gr.confinanz.tasks.management.service.impl;

import com.liferay.asset.kernel.model.AssetEntry;
import com.liferay.asset.kernel.model.AssetLinkConstants;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.model.ResourceConstants;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.search.Indexable;
import com.liferay.portal.kernel.search.IndexableType;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.util.CalendarFactoryUtil;
import com.liferay.portal.kernel.util.ContentTypes;
import com.liferay.portal.kernel.util.HtmlUtil;
import com.liferay.portal.kernel.util.PortalUtil;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

import aQute.bnd.annotation.ProviderType;
import gr.confinanz.tasks.management.exception.TaskTitleException;
import gr.confinanz.tasks.management.model.Task;
import gr.confinanz.tasks.management.service.base.TaskLocalServiceBaseImpl;

/**
 * The implementation of the task local service.
 *
 * <p>
 * All custom service methods should be put in this class. Whenever methods are added, rerun ServiceBuilder to copy their definitions into the {@link gr.confinanz.tasks.management.service.TaskLocalService} interface.
 *
 * <p>
 * This is a local service. Methods of this service will not have security checks based on the propagated JAAS credentials because this service can only be accessed from within the same VM.
 * </p>
 *
 * @author Abdessamad Ben Taleb
 * @see TaskLocalServiceBaseImpl
 * @see gr.confinanz.tasks.management.service.TaskLocalServiceUtil
 */
@ProviderType
public class TaskLocalServiceImpl extends TaskLocalServiceBaseImpl {
	
	@Indexable(type = IndexableType.REINDEX)
	@Override
	public Task addTask(
			long userId, String title, String description,
			int expirationDateMonth, int expirationDateDay,
			int expirationDateYear, long taskUserId, boolean completed,
			ServiceContext serviceContext)
		throws PortalException {

		User user = userPersistence.findByPrimaryKey(userId);
		userPersistence.findByPrimaryKey(taskUserId);

		long groupId = serviceContext.getScopeGroupId();

		validate(title);

		Date now = new Date();

		long taskId = counterLocalService.increment();

		Task task = taskPersistence.create(taskId);

		task.setUuid(serviceContext.getUuid());

		// Audit fields

		task.setGroupId(groupId);
		task.setCompanyId(user.getCompanyId());
		task.setUserId(user.getUserId());
		task.setUserName(user.getFullName());
		task.setCreateDate(serviceContext.getCreateDate(now));
		task.setModifiedDate(serviceContext.getModifiedDate(now));

		// Other fields

		Date expirationDate = PortalUtil.getDate(
			expirationDateMonth, expirationDateDay, expirationDateYear);

		task.setTitle(title);
		task.setDescription(description);
		task.setExpirationDate(expirationDate);
		task.setTaskUserId(taskUserId);
		task.setCompleted(completed);

		task = taskPersistence.update(task);

		// Resources

		resourceLocalService.addResources(
			task.getCompanyId(), task.getGroupId(), userId,
			Task.class.getName(), task.getTaskId(), false, true, true);

		// Asset

		updateAsset(
			userId, task, serviceContext.getAssetCategoryIds(),
			serviceContext.getAssetTagNames(),
			serviceContext.getAssetLinkEntryIds(),
			serviceContext.getAssetPriority());

		return task;
	}

	@Override
	public void deleteGroupTasks(long companyId, long groupId)
		throws PortalException {

		List<Task> tasks = taskPersistence.findByC_G(companyId, groupId);

		for (Task task : tasks) {
			deleteTask(task);
		}
	}

	@Override
	public Task deleteTask(long taskId) throws PortalException {
		Task task = taskPersistence.findByPrimaryKey(taskId);

		return deleteTask(task);
	}

	@Indexable(type = IndexableType.DELETE)
	public Task deleteTask(Task task) throws PortalException {

		// Task

		taskPersistence.remove(task);

		// Resources

		resourceLocalService.deleteResource(
			task.getCompanyId(), Task.class.getName(),
			ResourceConstants.SCOPE_INDIVIDUAL, task.getTaskId());

		// Asset

		assetEntryLocalService.deleteEntry(
			Task.class.getName(), task.getTaskId());

		return task;
	}

	@Override
	public void deleteUserTasks(long companyId, long userId)
		throws PortalException {

		List<Task> tasks = taskPersistence.findByC_T(companyId, userId);

		for (Task task : tasks) {
			deleteTask(task);
		}

		tasks = taskPersistence.findByC_U(companyId, userId);

		for (Task task : tasks) {
			deleteTask(task);
		}
	}

	@Override
	public Task getTask(long taskId) throws PortalException {
		return taskPersistence.findByPrimaryKey(taskId);
	}

	@Override
	public List<Task> getTasks(
		long companyId, long groupId, int start, int end) {

		return taskPersistence.findByC_G(companyId, groupId, start, end);
	}

	@Override
	public List<Task> getTasks(
		long companyId, long groupId, int status, int start, int end) {

		return taskPersistence.findByC_G_S(
			companyId, groupId, status, start, end);
	}

	@Override
	public int getTasksCount(long companyId, long groupId) {
		return taskPersistence.countByC_G(companyId, groupId);
	}

	@Override
	public int getTasksCount(long companyId, long groupId, int status) {
		return taskPersistence.countByC_G_S(companyId, groupId, status);
	}

	public void updateAsset(
			long userId, Task task, long[] assetCategoryIds,
			String[] assetTagNames, long[] assetLinkEntryIds, Double priority)
		throws PortalException {

		boolean visible = false;

		if (task.isApproved()) {
			visible = true;
		}

		String summary = HtmlUtil.extractText(
			StringUtil.shorten(task.getDescription(), 500));

		AssetEntry assetEntry = assetEntryLocalService.updateEntry(
			userId, task.getGroupId(), task.getCreateDate(),
			task.getModifiedDate(), Task.class.getName(), task.getTaskId(),
			task.getUuid(), 0, assetCategoryIds, assetTagNames, true, visible,
			task.getCreateDate(), null, null, null, ContentTypes.TEXT_HTML,
			task.getTitle(), task.getDescription(), summary, null, null, 0, 0,
			priority);

		assetLinkLocalService.updateLinks(
			userId, assetEntry.getEntryId(), assetLinkEntryIds,
			AssetLinkConstants.TYPE_RELATED);
	}

	@Indexable(type = IndexableType.REINDEX)
	@Override
	public Task updateTask(
			long userId, long taskId, String title, String description,
			int expirationDateMonth, int expirationDateDay,
			int expirationDateYear, long taskUserId, boolean completed,
			ServiceContext serviceContext)
		throws PortalException {

		// Task

		userPersistence.findByPrimaryKey(userId);
		Task task = taskPersistence.findByPrimaryKey(taskId);

		validate(title);

		Date now = new Date();

		// Audit fields

		task.setModifiedDate(serviceContext.getModifiedDate(now));

		// Other fields

		Calendar expirationDate = CalendarFactoryUtil.getCalendar();

		expirationDate.set(Calendar.MONTH, expirationDateMonth);
		expirationDate.set(Calendar.DATE, expirationDateDay);
		expirationDate.set(Calendar.YEAR, expirationDateYear);
		expirationDate.set(Calendar.HOUR_OF_DAY, 0);
		expirationDate.set(Calendar.MINUTE, 0);
		expirationDate.set(Calendar.SECOND, 0);
		expirationDate.set(Calendar.MILLISECOND, 0);

		task.setTitle(title);
		task.setDescription(description);
		task.setExpirationDate(expirationDate.getTime());
		task.setTaskUserId(taskUserId);
		task.setCompleted(completed);

		task = taskPersistence.update(task);

		// Asset

		updateAsset(
			userId, task, serviceContext.getAssetCategoryIds(),
			serviceContext.getAssetTagNames(),
			serviceContext.getAssetLinkEntryIds(),
			serviceContext.getAssetPriority());

		return task;
	}

	protected void validate(String title) throws PortalException {
		if (Validator.isNull(title)) {
			throw new TaskTitleException();
		}
	}


}