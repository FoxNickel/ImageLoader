package cn.foxnickel.imageloaderdemo;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.ImageView;

import cn.foxnickel.imageloader.ImageLoader;

/**
 * @author NickelFox
 * @date 2018/10/8
 */
public class MainActivity extends AppCompatActivity {

    private ImageView mIv;
    private static final String URL = "http://www.foxnickel.cn/ss8005.png";
//    private static final String URL = "http://img.my.csdn.net/uploads/201309/01/1378037235_7476.jpg";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mIv = findViewById(R.id.iv);
        ImageLoader imageLoader = ImageLoader.build(this);
        imageLoader.bindBitmap(URL, mIv);
    }
}
