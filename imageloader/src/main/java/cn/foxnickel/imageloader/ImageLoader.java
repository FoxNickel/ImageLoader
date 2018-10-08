package cn.foxnickel.imageloader;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Environment;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.jakewharton.disklrucache.DiskLruCache;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * @author NickelFox
 * @date 2018/10/8.
 */
public class ImageLoader {

    private static final int DISK_CACHE_SIZE = 50 * 1024 * 1024;
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAX_POOL_SIZE = 2 * CPU_COUNT + 1;
    private static final long KEEP_ALIVE_TIME = 10L;
    private static final int DISK_CACHE_INDEX = 0;

    private Context mContext;
    private final String TAG = getClass().getSimpleName();

    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;
    private ImageResizer mImageResizer = new ImageResizer();

    private static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {

        private AtomicInteger mAtomicInteger = new AtomicInteger(1);

        @Override
        public Thread newThread(@NonNull Runnable r) {
            return new Thread(r, "ImageLoader#" + mAtomicInteger.incrementAndGet());
        }
    };

    private static final ThreadPoolExecutor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAX_POOL_SIZE,
            KEEP_ALIVE_TIME,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            THREAD_FACTORY
    );

    private ImageLoader(Context context) {
        mContext = context.getApplicationContext();
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;

        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight() / 1024;
            }
        };

        File diskCacheDir = getDiskCacheDir(mContext, "bitmap");
        try {
//            if (getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE) {
            mDiskLruCache = DiskLruCache.open(diskCacheDir, 1, 1, DISK_CACHE_SIZE);
//            } else {
//                // TODO: 2018/10/8  用户储存空间不足处理
//            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void bindBitmap(String uri, ImageView imageView) {
        bindBitmap(uri, imageView, 0, 0);
    }

    /**
     * 异步加载接口
     *
     * @param uri
     * @param imageView
     * @param requireWidth
     * @param requireHeight
     */
    public void bindBitmap(final String uri, final ImageView imageView, final int requireWidth, final int requireHeight) {
        Bitmap bitmap = loadBitmapFromMemCache(uri);

        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            return;
        }

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = loadBitmap(uri, requireWidth, requireHeight);
                if (bitmap != null) {
                    imageView.post(new Runnable() {
                        @Override
                        public void run() {
                            imageView.setImageBitmap(bitmap);
                        }
                    });
                }
            }
        };
        THREAD_POOL_EXECUTOR.execute(runnable);
    }

    /**
     * 同步加载接口
     *
     * @param uri
     * @param requireWidth
     * @param requireHeight
     * @return
     */
    public Bitmap loadBitmap(String uri, int requireWidth, int requireHeight) {
        Bitmap bitmap = loadBitmapFromMemCache(uri);

        if (bitmap != null) {
            return bitmap;
        }

        bitmap = loadBitmapFromDiskCache(uri, requireWidth, requireHeight);
        if (bitmap != null) {
            return bitmap;
        }

        bitmap = loadBitmapFromNetwork(uri, requireWidth, requireHeight);
        return bitmap;
    }

    private Bitmap loadBitmapFromNetwork(String uri, int requireWidth, int requireHeight) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new RuntimeException("NetworkIO From UI Thread");
        }
        try {
            String key = hashKeyFromUrl(uri);
            DiskLruCache.Editor editor = mDiskLruCache.edit(key);
            if (editor != null) {
                OutputStream os = editor.newOutputStream(DISK_CACHE_INDEX);
                if (downloadUrlToStream(uri, os)) {
                    editor.commit();
                } else {
                    editor.abort();
                }
                mDiskLruCache.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return loadBitmapFromDiskCache(uri, requireWidth, requireHeight);
    }

    private boolean downloadUrlToStream(String uri, OutputStream os) {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(uri).build();
        Response response;
        InputStream is = null;
        try {
            response = client.newCall(request).execute();
            is = response.body().byteStream();
            int b;
            while ((b = is.read()) != -1) {
                os.write(b);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
                os.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    private Bitmap loadBitmapFromDiskCache(String uri, int requireWidth, int requireHeight) {
        if (mDiskLruCache == null) {
            return null;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "It's recommended that diskIO should not int mainThread!");
        }

        Bitmap bitmap = null;
        String key = hashKeyFromUrl(uri);
        try {
            DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
            if (snapshot != null) {
                FileInputStream fis = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
                FileDescriptor fd = fis.getFD();
                bitmap = mImageResizer.decodeSampledBitmapFromFileDescriptor(
                        fd,
                        requireWidth,
                        requireHeight
                );
                if (bitmap != null) {
                    addBitmapToMemoryCache(key, bitmap);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return bitmap;
    }

    private Bitmap loadBitmapFromMemCache(String uri) {
        String key = hashKeyFromUrl(uri);
        return getBitmapFromMemoryCache(key);
    }

    private String hashKeyFromUrl(String url) {
        String key;
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(url.getBytes());
            key = bytesToHexString(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            key = String.valueOf(url.hashCode());
        }
        return key;
    }

    private String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    public static ImageLoader build(Context context) {
        return new ImageLoader(context);
    }

    private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        mMemoryCache.put(key, bitmap);
    }

    private Bitmap getBitmapFromMemoryCache(String key) {
        return mMemoryCache.get(key);
    }

    private long getUsableSpace(File diskCacheDir) {
        long usableSpace = diskCacheDir.getUsableSpace();
        Log.i(TAG, "getUsableSpace: " + usableSpace);
        return usableSpace;
    }

    private File getDiskCacheDir(Context context, String dirName) {
        String cachePath;
        boolean externalStorageAvailable = Environment.getExternalStorageState()
                .equals(Environment.MEDIA_MOUNTED);
        if (externalStorageAvailable) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + dirName);
    }
}
