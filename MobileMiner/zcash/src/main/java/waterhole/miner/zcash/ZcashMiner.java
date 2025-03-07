package waterhole.miner.zcash;

import java.io.ObjectStreamException;
import waterhole.miner.core.WaterholeMiner;

/**
 * Zcash挖矿类.
 *
 * @author kzw on 2018/03/12.
 */
public final class ZcashMiner extends WaterholeMiner {

    private ZcashMiner() {
    }

    public static ZcashMiner instance() {
        return Holder.instance;
    }

    private static class Holder {
        static ZcashMiner instance = new ZcashMiner();
    }

    private Object readResolve() throws ObjectStreamException {
        return instance();
    }

    @Override
    public void startMine() {
        asserts();
        MineService.startService(getContext());
    }

    @Override
    public void stopMine() {
        asserts();
        MineService.stopService(getContext());
    }
}
