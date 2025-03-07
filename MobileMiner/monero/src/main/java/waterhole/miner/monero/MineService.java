/*
 *  Monero Miner App (c) 2018 Uwe Post
 *  based on the XMRig Monero Miner https://github.com/xmrig/xmrig
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program. If not, see <http://www.gnu.org/licenses/>.
 * /
 */

package waterhole.miner.monero;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import waterhole.miner.core.CallbackService;
import waterhole.miner.core.MineCallback;
import waterhole.miner.core.controller.AdjustController;
import waterhole.miner.core.controller.BaseController;
import waterhole.miner.core.controller.ITempTask;
import waterhole.miner.core.controller.TemperatureController;
import waterhole.miner.core.utils.LogUtils;
import waterhole.miner.core.utils.SpUtil;

import static waterhole.miner.core.asyn.AsyncTaskAssistant.executeOnThreadPool;
import static waterhole.miner.core.utils.APIUtils.hasJellyBean;
import static waterhole.miner.core.utils.LogUtils.error;
import static waterhole.miner.core.utils.LogUtils.info;
import static waterhole.miner.core.utils.LogUtils.errorWithReport;
import static waterhole.miner.core.analytics.AnalyticsSP.cacheCpuUse;
import static waterhole.miner.core.analytics.AnalyticsSP.cacheCpuUseThreads;
import static waterhole.miner.core.analytics.AnalyticsSP.cacheMineCoin;
import static waterhole.miner.core.analytics.AnalyticsSP.cacheMineScene;

public final class MineService extends Service implements ITempTask {

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private MineCallback mineCallback;

    private final static String[] SUPPORTED_ARCHITECTURES = {"armeabi", "armeabi-v7a", "arm64-v8a"};

    @Override
    public void start(final int[] temperatureSurface) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                TemperatureController.sCurUsageArr = temperatureSurface;
                startMine(temperatureSurface);
                error("current config>>>>>" + temperatureSurface[1] + ">>>usage>>" + temperatureSurface[2]);
            }
        });
    }

    @Override
    public void stop() {
        Intent intent = new Intent("waterhole.miner.monero.restart");
        sendBroadcast(intent);
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mineCallback = MineCallback.Stub.asInterface(service);
            try {
                mineCallback.onConnectPoolBegin();
            } catch (Exception e) {
                errorWithReport(getApplicationContext(), "MineService|ServiceConnection: " + e.getMessage());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    };

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null && action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                int level = intent.getIntExtra("level", 0);
                int status = intent.getIntExtra("status", BatteryManager.BATTERY_STATUS_UNKNOWN);
                info("battery level = " + level + " ,battery status = " + status);
                if (level <= 20) {
                    try {
                        XmrMiner.instance().stopMine();
                    } catch (Exception e) {
                        error(e.getMessage());
                    }
                }
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        info("MineService onBind");
        SpUtil.config(getApplicationContext());
        Context context = getApplicationContext();
        context.bindService(new Intent(this, CallbackService.class), serviceConnection, Context.BIND_AUTO_CREATE);

        BaseController[] controllerArr = {new TemperatureController(), new AdjustController()};
        for (int i = 0; i < controllerArr.length; i++) {
            controllerArr[i].setTask(this);
            controllerArr[i].startControl(context);
        }

        IMiningServiceBinder.MiningServiceBinder miningServiceBinder = new IMiningServiceBinder.MiningServiceBinder();
        miningServiceBinder.controller = (TemperatureController) controllerArr[0];

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(Intent.ACTION_BATTERY_LOW);
            registerReceiver(mBatteryReceiver, filter);
        } catch (Throwable throwable) {
            error(throwable.getMessage());
        }
        return miningServiceBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        info("MineService onDestroy");

        System.exit(0);
        android.os.Process.killProcess(android.os.Process.myPid());

        try {
            unregisterReceiver(mBatteryReceiver);
        } catch (Exception e) {
            error(e.getMessage());
        }
    }

    private void startMine(final int[] temperatureSurface) {
        do {
            SystemClock.sleep(100);

            if (mineCallback == null) continue;
            try {
                if (!hasJellyBean()) {
                    mineCallback.onMiningError("Android version must be >= 16");
                    return;
                }
                final String cpuABI = Build.CPU_ABI;
                info(cpuABI);
                if (!Arrays.asList(SUPPORTED_ARCHITECTURES).contains(cpuABI.toLowerCase())) {
                    mineCallback.onMiningError("Sorry, this app currently only supports " + Arrays.toString(SUPPORTED_ARCHITECTURES) + ", but yours is " + cpuABI);
                    return;
                }
            } catch (Exception e) {
                errorWithReport(getApplicationContext(), "MineService|startMine: " + e.getMessage());
            }
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    executeOnThreadPool(new Runnable() {
                        @Override
                        public void run() {
                            info("MineService startMine : threads=" + temperatureSurface[1] + " ,cpuUse=" + temperatureSurface[2]);
                            Context context = getApplicationContext();
                            cacheMineCoin(context, "xmr");
                            cacheCpuUseThreads(context, temperatureSurface[1]);
                            cacheCpuUse(context, temperatureSurface[2]);
                            cacheMineScene(context, "normal");

                            Xmr xmr = Xmr.instance();
                            xmr.startMine(temperatureSurface[1], temperatureSurface[2], mineCallback);
                        }
                    });
                }
            });
        } while (mineCallback == null);
    }
}
