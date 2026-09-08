package com.github.tvbox.osc.base;

import android.app.Activity;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import androidx.multidex.MultiDexApplication;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;
import com.github.catvod.crawler.JsLoader;

import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

import com.github.tvbox.osc.util.epg.EpgDataLoader;
import com.github.tvbox.osc.util.epg.EpgManager;
import com.github.tvbox.osc.util.FileLogger;
/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    private static App instance;

    private static P2PClass p;
    public static String burl;
    private static String dashData;

    @Override
    public void onCreate() {
        super.onCreate();
        // 初始化文件日志（按用户要求添加）
        FileLogger.init(this);

        instance = this;
        EpgUtil.init();

        // 全局异常捕获，防止闪退时看不到日志
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                String crashLog = sw.toString();

                // 写入内部存储的crash.log
                try {
                    File crashFile = new File(getFilesDir(), "crash.log");
                    FileWriter fw = new FileWriter(crashFile, true);
                    fw.write("\n===== " + new java.util.Date().toString() + " =====\n");
                    fw.write(crashLog);
                    fw.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                // 同时输出到系统日志
                LOG.e("APP_CRASH: " + crashLog);

                // 主线程上显示Toast（延迟确保能显示）
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(App.this, "应用崩溃，请查看 crash.log", Toast.LENGTH_LONG).show();
                        }
                    });
                }

                // 让系统默认处理器也处理（生成系统崩溃日志）
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });

        // 确保外部存储目录存在，防止某些库写日志时闪退
        ensureExternalDirs();

        // 逐个初始化，每个都加try-catch，防止一个失败导致全部失败
        safeInit("initParams", new Runnable() { @Override public void run() { initParams(); } });
        safeInit("OkGoHelper", new Runnable() { @Override public void run() { OkGoHelper.init(); } });
        safeInit("EpgUtil", new Runnable() { @Override public void run() { EpgUtil.init(); } });
        safeInit("ControlManager", new Runnable() { @Override public void run() { ControlManager.init(App.this); } });
        safeInit("AppDataManager", new Runnable() { @Override public void run() { AppDataManager.init(); } });
        safeInit("LoadSir", new Runnable() { @Override public void run() {
            LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .commit();
        }});
        safeInit("AutoSizeConfig", new Runnable() { @Override public void run() {
            AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        }});
        safeInit("PlayerHelper", new Runnable() { @Override public void run() { PlayerHelper.init(); } });
        safeInit("QuickJSLoader", new Runnable() { @Override public void run() { QuickJSLoader.init(); } });
        safeInit("cleanPlayerCache", new Runnable() { @Override public void run() { FileUtils.cleanPlayerCache(); } });

        // ========== 新增 EPG 初始化并添加日志（按用户要求修改） ==========
        try {
            EpgDataLoader.load(this);
            Log.i("Epg", "EpgDataLoader 加载完成");
            EpgManager.getInstance(this).refreshEpg(new EpgManager.RefreshCallback() {
                @Override
                public void onSuccess() {
                    Log.i("Epg", "EPG 刷新成功");
                }
                @Override
                public void onError(String msg) {
                    Log.e("Epg", "EPG 刷新失败: " + msg);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        // ========== 新增结束 ==========
    }

    private void ensureExternalDirs() {
        try {
            // 方式1：通过API获取并创建
            File extFiles = getExternalFilesDir(null);
            if (extFiles != null && !extFiles.exists()) {
                extFiles.mkdirs();
            }
            // 再创建子目录
            if (extFiles != null) {
                File logDir = new File(extFiles.getParentFile(), "files");
                if (!logDir.exists()) logDir.mkdirs();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            // 方式2：直接创建sdcard路径（兼容某些电视盒子）
            File sdcardPath = new File("/sdcard/Android/data/" + getPackageName() + "/files");
            if (!sdcardPath.exists()) {
                sdcardPath.mkdirs();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            // 方式3：直接创建data/media路径
            File mediaPath = new File("/data/media/0/Android/data/" + getPackageName() + "/files");
            if (!mediaPath.exists()) {
                mediaPath.mkdirs();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            // 方式4：创建内部存储的files目录（兜底）
            File internalFiles = getFilesDir();
            if (internalFiles != null && !internalFiles.exists()) {
                internalFiles.mkdirs();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void safeInit(String name, Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            LOG.e("Init failed [" + name + "]: " + t.getMessage());
            t.printStackTrace();
            // 不抛出，继续初始化其他模块
        }
    }

    private void initParams() {
        // Hawk
        Hawk.init(this).build();
        Hawk.put(HawkConfig.DEBUG_OPEN, false);
        if (!Hawk.contains(HawkConfig.PLAY_TYPE)) {
            Hawk.put(HawkConfig.PLAY_TYPE, 1);
        }
    }

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        try {
            JsLoader.destroy();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private VodInfo vodInfo;
    public void setVodInfo(VodInfo vodinfo){
        this.vodInfo = vodinfo;
    }
    public VodInfo getVodInfo(){
        return this.vodInfo;
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(FileUtils.getExternalCachePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }

    public Activity getCurrentActivity() {
        return AppManager.getInstance().currentActivity();
    }

    public void setDashData(String data) {
        dashData = data;
    }
    public String getDashData() {
        return dashData;
    }
}
