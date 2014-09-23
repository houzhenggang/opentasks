/*
 * Copyright (C) 2014 Marten Gajda <marten@dmfs.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package org.dmfs.tasks.dashclock;

import java.util.Calendar;
import java.util.TimeZone;

import org.dmfs.provider.tasks.TaskContract;
import org.dmfs.provider.tasks.TaskContract.Instances;
import org.dmfs.provider.tasks.TaskContract.Tasks;
import org.dmfs.tasks.EditTaskActivity;
import org.dmfs.tasks.R;
import org.dmfs.tasks.model.adapters.TimeFieldAdapter;
import org.dmfs.tasks.utils.DueDateFormatter;

import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.preference.PreferenceManager;
import android.text.format.Time;

import com.google.android.apps.dashclock.api.DashClockExtension;
import com.google.android.apps.dashclock.api.ExtensionData;


/**
 * This class provides an extension for the DashClock widget in order to displays recent tasks.
 * 
 * @author Tobias Reinsch <tobias@dmfs.org>
 * 
 */
public class TasksExtension extends DashClockExtension
{
	/** Defines the time span for recent tasks in hours **/
	private static final int RECENT_HOURS = 3;

	private static final String[] INSTANCE_PROJECTION = new String[] { Instances._ID, Instances.TASK_ID, Instances.ACCOUNT_NAME, Instances.ACCOUNT_TYPE,
		Instances.TITLE, Instances.DESCRIPTION, Instances.STATUS, Instances.DUE, Instances.DTSTART, Instances.TZ, Instances.IS_ALLDAY };

	private static final String INSTANCE_DUE_SELECTION = Instances.IS_ALLDAY + " = 0 AND " + Instances.STATUS + " != " + Instances.STATUS_COMPLETED + " AND "
		+ Instances.STATUS + " != " + Instances.STATUS_CANCELLED + " AND (" + Instances.DUE + " > ? AND " + Instances.DUE + " < ? )";
	private static final String INSTANCE_START_SELECTION = Instances.IS_ALLDAY + " = 0 AND " + Instances.STATUS + " != " + Instances.STATUS_COMPLETED + " AND "
		+ Instances.STATUS + " != " + Instances.STATUS_CANCELLED + " AND (" + Instances.DTSTART + " > ? AND " + Instances.DTSTART + " < ? )";
	private static final String INSTANCE_START_DUE_SELECTION = Instances.IS_ALLDAY + " = 0 AND " + Instances.STATUS + " != " + Instances.STATUS_COMPLETED
		+ " AND " + Instances.STATUS + " != " + Instances.STATUS_CANCELLED + " AND ((" + Instances.DTSTART + " > ? AND " + Instances.DTSTART + " < ? ) OR ( "
		+ Instances.DUE + " > ? AND " + Instances.DUE + " < ? ))";

	private static final String INSTANCE_START_SELECTION_ALL_DAY = Instances.IS_ALLDAY + " = 1 AND " + Instances.STATUS + " != " + Instances.STATUS_COMPLETED
		+ " AND " + Instances.STATUS + " != " + Instances.STATUS_CANCELLED + " AND (" + Instances.DTSTART + " = ?)";

	private static final String INSTANCE_DUE_SELECTION_ALL_DAY = Instances.IS_ALLDAY + " = 1 AND " + Instances.STATUS + " != " + Instances.STATUS_COMPLETED
		+ " AND " + Instances.STATUS + " != " + Instances.STATUS_CANCELLED + " AND (" + Instances.DUE + " = ?)";

	private static final String INSTANCE_START_DUE_SELECTION_ALL_DAY = Instances.IS_ALLDAY + " = 1 AND " + Instances.STATUS + " != "
		+ Instances.STATUS_COMPLETED + " AND " + Instances.STATUS + " != " + Instances.STATUS_CANCELLED + " AND (" + Instances.DTSTART + " = ? OR "
		+ Instances.DUE + " = ? )";

	private String mAuthority;
	private int mDisplayMode;
	private long mNow;


	@Override
	protected void onInitialize(boolean isReconnect)
	{
		// enable automatic dashclock updates on task changes
		addWatchContentUris(new String[] { TaskContract.getContentUri(getString(R.string.org_dmfs_tasks_authority)).toString() });
		super.onInitialize(isReconnect);
	}


	@Override
	protected void onUpdateData(int reason)
	{
		mNow = System.currentTimeMillis();
		mAuthority = getString(R.string.org_dmfs_tasks_authority);
		SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
		mDisplayMode = Integer.valueOf(sharedPref.getString(DashClockPreferenceActivity.KEY_PREF_DISPLAY_MODE, "1"));
		publishRecentTaskUpdate();
	}


	protected void publishRecentTaskUpdate()
	{

		// get next task that is due
		Cursor recentTaskCursor;
		Cursor allDayTaskCursor;

		switch (mDisplayMode)
		{
			case DashClockPreferenceActivity.DISPLAY_MODE_DUE:
				recentTaskCursor = loadRecentDueTaskCursor();
				allDayTaskCursor = loadAllDayTasksDueTodayCursor();
				break;

			case DashClockPreferenceActivity.DISPLAY_MODE_START:
				recentTaskCursor = loadRecentStartTaskCursor();
				allDayTaskCursor = loadAllDayTasksStartTodayCursor();
				break;

			default:
				recentTaskCursor = loadRecentStartDueTaskCursor();
				allDayTaskCursor = loadAllDayTasksStartDueTodayCursor();
				break;
		}

		int recentTaskCount = recentTaskCursor.getCount();
		int allDayTaskCount = allDayTaskCursor.getCount();
		if ((recentTaskCount + allDayTaskCount) > 0)
		{
			// select the right cursor
			Cursor c = recentTaskCount > 0 ? recentTaskCursor : allDayTaskCursor;
			c.moveToFirst();

			boolean isAllDay = recentTaskCount == 0;

			String description = c.getString(c.getColumnIndex(Tasks.DESCRIPTION));
			description = description.replaceAll("\\[\\s?\\]", " ").replaceAll("\\[[xX]\\]", "✓");
			String title = getTaskTitleDisplayString(c, isAllDay);

			// intent
			String accountType = c.getString(c.getColumnIndex(Instances.ACCOUNT_TYPE));
			Long taskId = c.getLong(c.getColumnIndex(Instances.TASK_ID));
			Intent clickIntent = buildClickIntent(taskId, accountType);

			// Publish the extension data update.
			publishUpdate(new ExtensionData().visible(true).icon(R.drawable.ic_dashboard).status(String.valueOf(allDayTaskCount + recentTaskCount))
				.expandedTitle(title).expandedBody(description).clickIntent(clickIntent));
		}
		else
		{
			// no upcoming task -> empty update
			publishUpdate(null);
		}

	}


	private String getTaskTitleDisplayString(Cursor c, boolean isAllDay)
	{
		if (DashClockPreferenceActivity.DISPLAY_MODE_DUE == mDisplayMode)
		{
			// DUE event
			return getTaskTitleDueString(c, isAllDay);
		}
		else if (DashClockPreferenceActivity.DISPLAY_MODE_START == mDisplayMode)
		{
			// START event
			return getTaskTitleStartString(c, isAllDay);
		}
		else
		{
			// START or DUE event
			return isDueEvent(c, isAllDay) ? getTaskTitleDueString(c, isAllDay) : getTaskTitleStartString(c, isAllDay);
		}
	}


	private String getTaskTitleDueString(Cursor c, boolean isAllDay)
	{
		DueDateFormatter formatter = new DueDateFormatter(this, DueDateFormatter.TIME_DATEUTILS_FLAGS);
		if (isAllDay)
		{
			return getString(R.string.dashclock_widget_title_due_expanded_allday, c.getString(c.getColumnIndex(Tasks.TITLE)));
		}
		else
		{
			TimeFieldAdapter timeFieldAdapter = new TimeFieldAdapter(Instances.DUE, Instances.TZ, Instances.IS_ALLDAY);
			Time dueTime = timeFieldAdapter.get(c);
			String dueTimeString = formatter.format(dueTime, false);
			return getString(R.string.dashclock_widget_title_due_expanded, c.getString(c.getColumnIndex(Tasks.TITLE)), dueTimeString);
		}
	}


	private String getTaskTitleStartString(Cursor c, boolean isAllDay)
	{
		DueDateFormatter formatter = new DueDateFormatter(this, DueDateFormatter.TIME_DATEUTILS_FLAGS);
		if (isAllDay)
		{
			return getString(R.string.dashclock_widget_title_start_expanded_allday, c.getString(c.getColumnIndex(Tasks.TITLE)));
		}
		else
		{
			TimeFieldAdapter timeFieldAdapter = new TimeFieldAdapter(Instances.DTSTART, Instances.TZ, Instances.IS_ALLDAY);
			Time startTime = timeFieldAdapter.get(c);
			String startTimeString = formatter.format(startTime, false);
			return getString(R.string.dashclock_widget_title_start_expanded, c.getString(c.getColumnIndex(Tasks.TITLE)), startTimeString);
		}
	}


	private boolean isDueEvent(Cursor c, boolean isAllDay)
	{
		if (c.isNull(c.getColumnIndex(Instances.DUE)))
		{
			return false;
		}
		if (c.isNull(c.getColumnIndex(Instances.DTSTART)))
		{
			return true;
		}

		Long dueTime = c.getLong(c.getColumnIndex(Instances.DUE));
		Long startTime = c.getLong(c.getColumnIndex(Instances.DTSTART));

		if (isAllDay)
		{
			// get start of today in UTC
			Calendar calendar = Calendar.getInstance();
			calendar.set(Calendar.HOUR_OF_DAY, 0); // clear would not reset the hour of day
			calendar.clear(Calendar.MINUTE);
			calendar.clear(Calendar.SECOND);
			calendar.clear(Calendar.MILLISECOND);
			calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
			long todayUTC = calendar.getTimeInMillis();

			if (dueTime == todayUTC)
			{
				return true;
			}
			else
			{
				return false;
			}
		}
		else
		{
			if (startTime < mNow)
			{
				return true;
			}
			else
			{
				return false;
			}
		}

	}


	protected Intent buildClickIntent(long taskId, String accountType)
	{
		Intent clickIntent = new Intent(Intent.ACTION_VIEW);
		clickIntent.setData(ContentUris.withAppendedId(Tasks.getContentUri(mAuthority), taskId));
		clickIntent.putExtra(EditTaskActivity.EXTRA_DATA_ACCOUNT_TYPE, accountType);

		return clickIntent;
	}


	private Cursor loadRecentDueTaskCursor()
	{
		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.HOUR_OF_DAY, RECENT_HOURS); // clear would not reset the hour of day
		long later = calendar.getTimeInMillis();

		return getContentResolver().query(Instances.getContentUri(mAuthority), INSTANCE_PROJECTION, INSTANCE_DUE_SELECTION,
			new String[] { String.valueOf(mNow), String.valueOf(later) }, Instances.DUE);
	}


	private Cursor loadRecentStartTaskCursor()
	{
		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.HOUR_OF_DAY, RECENT_HOURS); // clear would not reset the hour of day
		long later = calendar.getTimeInMillis();

		return getContentResolver().query(Instances.getContentUri(mAuthority), INSTANCE_PROJECTION, INSTANCE_START_SELECTION,
			new String[] { String.valueOf(mNow), String.valueOf(later) }, Instances.DTSTART);
	}


	private Cursor loadRecentStartDueTaskCursor()
	{
		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.HOUR_OF_DAY, RECENT_HOURS); // clear would not reset the hour of day
		long later = calendar.getTimeInMillis();

		return getContentResolver().query(Instances.getContentUri(mAuthority), INSTANCE_PROJECTION, INSTANCE_START_DUE_SELECTION,
			new String[] { String.valueOf(mNow), String.valueOf(later), String.valueOf(mNow), String.valueOf(later) },
			Instances.INSTANCE_DUE_SORTING + " is null, " + Instances.INSTANCE_DUE_SORTING);
	}


	private Cursor loadAllDayTasksDueTodayCursor()
	{
		// get start of today in UTC
		Calendar calendar = Calendar.getInstance();
		calendar.set(Calendar.HOUR_OF_DAY, 0); // clear would not reset the hour of day
		calendar.clear(Calendar.MINUTE);
		calendar.clear(Calendar.SECOND);
		calendar.clear(Calendar.MILLISECOND);
		calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
		long todayUTC = calendar.getTimeInMillis();

		return getContentResolver().query(Instances.getContentUri(mAuthority), INSTANCE_PROJECTION, INSTANCE_DUE_SELECTION_ALL_DAY,
			new String[] { String.valueOf(todayUTC) }, Instances.DUE);
	}


	private Cursor loadAllDayTasksStartTodayCursor()
	{
		// get start of today in UTC
		Calendar calendar = Calendar.getInstance();
		calendar.set(Calendar.HOUR_OF_DAY, 0); // clear would not reset the hour of day
		calendar.clear(Calendar.MINUTE);
		calendar.clear(Calendar.SECOND);
		calendar.clear(Calendar.MILLISECOND);
		calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
		long todayUTC = calendar.getTimeInMillis();

		return getContentResolver().query(Instances.getContentUri(mAuthority), INSTANCE_PROJECTION, INSTANCE_START_SELECTION_ALL_DAY,
			new String[] { String.valueOf(todayUTC) }, Instances.DTSTART);
	}


	private Cursor loadAllDayTasksStartDueTodayCursor()
	{
		// get start of today in UTC
		Calendar calendar = Calendar.getInstance();
		calendar.set(Calendar.HOUR_OF_DAY, 0); // clear would not reset the hour of day
		calendar.clear(Calendar.MINUTE);
		calendar.clear(Calendar.SECOND);
		calendar.clear(Calendar.MILLISECOND);
		calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
		long todayUTC = calendar.getTimeInMillis();

		return getContentResolver().query(Instances.getContentUri(mAuthority), INSTANCE_PROJECTION, INSTANCE_START_DUE_SELECTION_ALL_DAY,
			new String[] { String.valueOf(todayUTC), String.valueOf(todayUTC) }, Instances.DUE);
	}
}
