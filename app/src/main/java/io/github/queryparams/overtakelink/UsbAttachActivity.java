package io.github.queryparams.overtakelink;

import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

import com.andrerinas.openheadunit.BuildConfig;
import com.andrerinas.openheadunit.aap.AapService;

/**
 * Невидимый экран, который Android открывает при подключении машины (USB-аксессуар QDriveLink):
 * разрешение на аксессуар выдаётся ему, и он сразу передаёт подключение сервису OvertakeLink.
 */
public final class UsbAttachActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!Terms.accepted(this)) {
            // Работать с машиной — только после принятия условий: открываем их вместо подключения.
            // В задаче приложения, а не в этой: иначе следующее подключение кабеля лишь вынесет
            // экран на передний план, не запустив подключение.
            startActivity(new Intent(this, OvertakeLinkActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        String sim = BuildConfig.SIM_LINK ? getIntent().getStringExtra(OvertakeLinkService.EXTRA_SIM) : null;
        if (sim == null && !attachedNow()) {
            // Открыли не по подключению кабеля (например, другое приложение напрямую): работающий сеанс
            // не трогаем — иначе подключение «заново» оборвало бы проекцию.
            finish();
            return;
        }
        getSharedPreferences("overtakelink", MODE_PRIVATE).edit().putBoolean("usbSeen", true).apply();
        if (sim == null && io.github.queryparams.overtakelink.gap.GapService.selected(this)) {
            // Выбран вывод в GAP Smart Tool по Wi-Fi: кабель — только для зарядки, QDLink не трогаем.
            io.github.queryparams.overtakelink.link.EventLog.add("USB: машина подключена, но выбран вывод в GAP Smart Tool — через QDLink не подключаемся");
            finish();
            return;
        }
        startForegroundService(sim == null
                ? new Intent(this, OvertakeLinkService.class).setAction(OvertakeLinkService.ACTION_CONNECT_USB)
                : new Intent(this, OvertakeLinkService.class).setAction(OvertakeLinkService.ACTION_CONNECT_SIM)
                        .putExtra(OvertakeLinkService.EXTRA_SIM, sim));
        // Приёмник Android Auto поднимаем в foreground сейчас, пока этот экран виден: из фона Android 12+
        // такой запуск запрещает. Команду «включить режим» OvertakeLink пошлёт ему позже.
        startForegroundService(new Intent(this, AapService.class));
        finish();
    }

    /** Android сообщил о подключении аксессуара, и это тот, что сейчас подключён. */
    private boolean attachedNow() {
        Intent i = getIntent();
        if (!UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(i.getAction())) {
            return false;
        }
        // Перегрузка с классом — только с Android 13, а приложение работает с 12.
        @SuppressWarnings("deprecation")
        UsbAccessory acc = i.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        UsbAccessory[] list = getSystemService(UsbManager.class).getAccessoryList();
        return acc != null && list != null && list.length > 0 && acc.equals(list[0]);
    }
}
