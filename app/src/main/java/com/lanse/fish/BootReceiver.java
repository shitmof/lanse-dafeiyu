package com.lanse.fish;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        if(!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction()))return;
        Prefs prefs=new Prefs(c);
        if(!prefs.flag("autostart",false)||!prefs.flag("enabled",false)||!Settings.canDrawOverlays(c))return;
        try{PetService.command(c,PetService.REFRESH,"");}catch(RuntimeException e){
            prefs.put("last_error","系统阻止了开机启动，请打开应用点「开始陪伴」。");
            if(Build.VERSION.SDK_INT<33||c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED){
                PetService.createNotificationChannel(c);PendingIntent open=PendingIntent.getActivity(c,3,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
                ((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(46,new Notification.Builder(c,"pet").setSmallIcon(R.drawable.ic_fish).setContentTitle("蓝色大肥鱼等你回来")
                    .setContentText("点此恢复桌宠陪伴").setContentIntent(open).setAutoCancel(true).build());
            }
        }
    }
}
