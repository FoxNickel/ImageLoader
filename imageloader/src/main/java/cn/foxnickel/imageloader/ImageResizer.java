package cn.foxnickel.imageloader;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.FileDescriptor;

/**
 * @author NickelFox
 * @date 2018/10/8.
 */
public class ImageResizer {
    private final String TAG = getClass().getSimpleName();

    public ImageResizer() {
    }

    public Bitmap decodeSampledBitmapFromResource(Resources resources,
                                                  int resId,
                                                  int requireWidth,
                                                  int requireHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(resources, resId, options);

        options.inJustDecodeBounds = false;
        options.inSampleSize = getInSampleSize(options, requireWidth, requireHeight);

        return BitmapFactory.decodeResource(resources, resId, options);
    }

    public Bitmap decodeSampledBitmapFromFileDescriptor(FileDescriptor fd,
                                                        int requireWidth,
                                                        int requireHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(fd, null, options);

        options.inJustDecodeBounds = false;
        options.inSampleSize = getInSampleSize(options, requireWidth, requireHeight);

        return BitmapFactory.decodeFileDescriptor(fd, null, options);
    }

    private int getInSampleSize(BitmapFactory.Options options, int requireWidth, int requireHeight) {
        if (requireWidth == 0 || requireHeight == 0) {
            return 1;
        }

        int oldWidth = options.outWidth;
        int oldHeight = options.outHeight;
        int inSampleSize = 1;

        if (oldWidth > requireWidth || oldHeight > requireHeight) {
            // 当图片长宽之一比require的大的时候就要缩放
            inSampleSize = Math.min(oldWidth / requireWidth, oldHeight / requireHeight);
        }

        return inSampleSize;
    }
}
