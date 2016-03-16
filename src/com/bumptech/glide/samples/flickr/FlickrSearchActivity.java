package com.bumptech.glide.samples.flickr;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.StrictMode;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.view.ViewPager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.prefill.PreFillType;
import com.bumptech.glide.request.FutureTarget;
import com.bumptech.glide.samples.flickr.api.Api;
import com.bumptech.glide.samples.flickr.api.Photo;
import com.bumptech.glide.R;

/**
 * An activity that allows users to search for images on Flickr and that
 * contains a series of fragments that display retrieved image thumbnails.
 */
public class FlickrSearchActivity extends FragmentActivity {
	private static final String TAG = "FlickrSearchActivity";
	private static final String STATE_QUERY = "state_search_string";

	private View searching;
	private TextView searchTerm;
	private Set<PhotoViewer> photoViewers = new HashSet<>();
	private List<Photo> currentPhotos = new ArrayList<>();
	private View searchLoading;
	private BackgroundThumbnailFetcher backgroundThumbnailFetcher;
	private HandlerThread backgroundThread;
	private Handler backgroundHandler;

	private enum Page {
		SMALL, MEDIUM, LIST
	}

	private static final Map<Page, Integer> PAGE_TO_TITLE = new HashMap<Page, Integer>() {
		{
			put(Page.SMALL, R.string.small);
			put(Page.MEDIUM, R.string.medium);
			put(Page.LIST, R.string.list);
		}
	};

	@Override
	public void onAttachFragment(Fragment fragment) {
		super.onAttachFragment(fragment);
		if (fragment instanceof PhotoViewer) {
			PhotoViewer photoViewer = (PhotoViewer) fragment;
			photoViewer.onPhotosUpdated(currentPhotos);
			if (!photoViewers.contains(photoViewer)) {
				photoViewers.add(photoViewer);
			}
		}
	}

	/**
	 * Called when the activity is first created.
	 */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
				.detectAll().penaltyLog().build());

		backgroundThread = new HandlerThread("BackgroundThumbnailHandlerThread");
		backgroundThread.start();
		backgroundHandler = new Handler(backgroundThread.getLooper());

		setContentView(R.layout.flickr_search_activity);
		searching = findViewById(R.id.searching);
		searchLoading = findViewById(R.id.search_loading);
		searchTerm = (TextView) findViewById(R.id.search_term);

		Resources res = getResources();
		ViewPager pager = (ViewPager) findViewById(R.id.view_pager);
		pager.setPageMargin(res.getDimensionPixelOffset(R.dimen.page_margin));
		pager.setAdapter(new FlickrPagerAdapter(getSupportFragmentManager()));

		int smallGridSize = res.getDimensionPixelSize(R.dimen.small_photo_side);
		int mediumGridSize = res
				.getDimensionPixelSize(R.dimen.medium_photo_side);
		int listHeightSize = res
				.getDimensionPixelSize(R.dimen.flickr_list_item_height);
		int screenWidth = getScreenWidth();

		if (savedInstanceState == null) {
			// Weight values determined experimentally by measuring the number
			// of incurred GCs while
			// scrolling through the various photo grids/lists.
			Glide.get(this).preFillBitmapPool(
					new PreFillType.Builder(smallGridSize).setWeight(1),
					new PreFillType.Builder(mediumGridSize).setWeight(1),
					new PreFillType.Builder(screenWidth / 2, listHeightSize)
							.setWeight(6));
		}
	}

	private int getScreenWidth() {
		return getResources().getDisplayMetrics().widthPixels;
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (backgroundThumbnailFetcher != null) {
			backgroundThumbnailFetcher.cancel();
			backgroundThumbnailFetcher = null;
			backgroundThread.quit();
			backgroundThread = null;
		}
	}

	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
	@Override
	public void onTrimMemory(int level) {
		super.onTrimMemory(level);
		Glide.get(this).trimMemory(level);
	}

	@Override
	public void onLowMemory() {
		super.onLowMemory();
		Glide.get(this).clearMemory();
	}

	private class FlickrPagerAdapter extends FragmentPagerAdapter {

		private int mLastPosition = -1;
		private Fragment mLastFragment;

		public FlickrPagerAdapter(FragmentManager fm) {
			super(fm);
		}

		@Override
		public Fragment getItem(int position) {
			return pageToFragment(position);
		}

		@Override
		public void setPrimaryItem(ViewGroup container, int position,
				Object object) {
			super.setPrimaryItem(container, position, object);
			if (position != mLastPosition) {
				if (mLastPosition >= 0) {
					Glide.with(mLastFragment).pauseRequests();
				}
				Fragment current = (Fragment) object;
				mLastPosition = position;
				mLastFragment = current;
				if (current.isAdded()) {
					Glide.with(current).resumeRequests();
				}
			}
		}

		@Override
		public int getCount() {
			return Page.values().length;
		}

		@Override
		public CharSequence getPageTitle(int position) {
			Page page = Page.values()[position];
			int titleId = PAGE_TO_TITLE.get(page);
			return getString(titleId);
		}

		private Fragment pageToFragment(int position) {
			Page page = Page.values()[position];
			if (page == Page.SMALL) {
				int pageSize = getPageSize(R.dimen.small_photo_side);
				return FlickrPhotoGrid
						.newInstance(pageSize, 15, false /* thumbnail */);
			} else if (page == Page.MEDIUM) {
				int pageSize = getPageSize(R.dimen.medium_photo_side);
				return FlickrPhotoGrid
						.newInstance(pageSize, 10, true /* thumbnail */);
			} else if (page == Page.LIST) {
				return FlickrPhotoList.newInstance();
			} else {
				throw new IllegalArgumentException(
						"No fragment class for page=" + page);
			}
		}

		private int getPageSize(int id) {
			return getResources().getDimensionPixelSize(id);
		}
	}

	private static class BackgroundThumbnailFetcher implements Runnable {
		private boolean isCancelled;
		private Context context;
		private List<Photo> photos;

		public BackgroundThumbnailFetcher(Context context, List<Photo> photos) {
			this.context = context;
			this.photos = photos;
		}

		public void cancel() {
			isCancelled = true;
		}

		@Override
		public void run() {
			Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST);
			for (Photo photo : photos) {
				if (isCancelled) {
					return;
				}

				FutureTarget<File> futureTarget = Glide.with(context)
						.downloadOnly().load(photo)
						.submit(Api.SQUARE_THUMB_SIZE, Api.SQUARE_THUMB_SIZE);

				try {
					futureTarget.get();
				} catch (InterruptedException e) {
					if (Log.isLoggable(TAG, Log.DEBUG)) {
						Log.d(TAG,
								"Interrupted waiting for background downloadOnly",
								e);
					}
				} catch (ExecutionException e) {
					if (Log.isLoggable(TAG, Log.DEBUG)) {
						Log.d(TAG,
								"Got ExecutionException waiting for background downloadOnly",
								e);
					}
				}
				Glide.with(context).clear(futureTarget);
			}
		}
	}
}
