package byr.ipv4over6;

import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Date;
import java.util.Enumeration;
import java.util.Timer;
import java.util.TimerTask;
//MainActivity 的主要任务是初始化全局的变量、启动两个后台的线程并打开 VPN 服务。首先主线程通过 getLocalIpAddress 函数获取本机的 IPV6 地址，只 有成功获取到地址才会继续后续的服务。其次为启动服务按钮注册监听事件，若按下 则会启动两个后台的线程 IVI 和 BackGround。

public class MainActivity extends AppCompatActivity {
//UI主线程
    private Button startButton;
    private boolean startstop;
    private Date startTime;
    private TextView time_info;
    private TextView speed_info;
    private TextView send_info;
    private TextView receive_info;
    private TextView ipv4_info;
    private TextView ipv6_info;

    //  后台 vpn service
    private Thread houtai, vpns;

    class IpPacket{
        String ipAddress;
        String route;
        String DNS1,DNS2,DNS3;
        String socket;
    };
    IpPacket sergateway;

    class GuiInfo{
        String time_duration;
        String speed_info;
        String send_info;
        String receive_info;
        String ipv4_addr;
        String ipv6_addr;
    };

//    初始化全局的变量
    final String ipHandleName = "/data/data/byr.ipv4over6/myfifo";
    final String statsHandleName = "/data/data/byr.ipv4over6/myfifo_stats";

    private long sendBytes, receiveBytes, sendPackets, receivePackets;

    private Intent myintent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        File destDir = new File("/data/data/byr.ipv4over6");
        if (!destDir.exists()) {
            destDir.mkdirs();
        }



        startButton = (Button) findViewById(R.id.start);
        time_info = (TextView) findViewById(R.id.time);
        speed_info = (TextView) findViewById(R.id.speed);
        send_info = (TextView) findViewById(R.id.send);
        receive_info = (TextView) findViewById(R.id.receive);
        ipv4_info = (TextView) findViewById(R.id.ip4);
        ipv6_info = (TextView) findViewById(R.id.ip6);




        startstop = false;
        startButton.setOnClickListener(new View.OnClickListener() {
//            启动两个后台的线程并打开 VPN 服务
            @Override
            public void onClick(View v) {
                final GuiInfo show = new GuiInfo();
                if (startstop == true){
                    startstop = false;
                    startButton.setText("start");
                }
                else{
                    startstop = true;
                    startButton.setText("stop");
                }
                show.ipv6_addr = getLocalIpAddress();
                Log.d("byr", show.ipv6_addr);
                //  关闭连接
                if (startstop == false){
                    File file = new File(ipHandleName);
                    try {
                        FileOutputStream fileOutputStream = new FileOutputStream(file);
                        BufferedOutputStream out = new BufferedOutputStream(fileOutputStream);
                        byte goodbye[] = "999\n".getBytes();
                        //Notify the background C thread
                        out.write(goodbye, 0, goodbye.length);
                        out.flush();
                        out.close();
                        vpns.interrupt();
                        stopService(myintent);
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }
                else if (startstop == true && show.ipv6_addr != null){
                    houtai = new Thread(){
                        public void run(){
                            connect4o6Byr();
                        }
                    };
                    houtai.start();


                    //  更新 UI
                    final Handler handler = new Handler(){
                        @Override
                        public void handleMessage(Message msg) {
                            switch (msg.what) {
                                case 1:
                                    GuiInfo guiInfo = (GuiInfo)msg.obj;
                                    time_info.setText(guiInfo.time_duration);
                                    speed_info.setText(guiInfo.speed_info);
                                    send_info.setText(guiInfo.send_info);
                                    receive_info.setText(guiInfo.receive_info);
                                    ipv4_info.setText(guiInfo.ipv4_addr);
                                    ipv6_info.setText(guiInfo.ipv6_addr);
                                    break;
                            }
                            super.handleMessage(msg);
                        }
                    };

                    vpns = new Thread(){
                        public void run(){
                            //  获取服务器IP等用来建立VPN
                            sergateway = getSerGatewayInfo();

                            show.ipv4_addr = sergateway.ipAddress;
                            vpnConnect();
                            startTime = new Date();
                            Timer timer = new Timer();

                            // 延时0.5秒后执行。每隔1秒执行1次task
                            timer.schedule(new TimerTask() {
                                @Override
                                public void run() {
                                    File file = new File(statsHandleName);
                                    while (!file.exists()){
                                        try {
                                            Thread.sleep(100);
                                        }catch (InterruptedException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                    try{
                                        FileInputStream fis = new FileInputStream(file);
                                        BufferedInputStream in = new BufferedInputStream(fis);
                                        byte buf[] = new byte[1024];
                                        int rl = in.read(buf, 0, 1024);
                                        if (rl > 0){
                                            String ipsi = new String(buf);
                                            String[] piece = ipsi.split(" ");

                                            show.speed_info = "Upload "+piece[0]+" Bytes/s  Download "+piece[2]+" Bytes/s";
                                            sendBytes += Long.parseLong(piece[0]);
                                            sendPackets += Long.parseLong(piece[1]);
                                            receiveBytes += Long.parseLong(piece[2]);
                                            receivePackets += Long.parseLong(piece[3]);
                                            show.send_info = Long.toString(sendBytes) + " Bytes / " + Long.toString(sendPackets) + " packets";
                                            show.receive_info = Long.toString(receiveBytes) + " Bytes / " + Long.toString(receivePackets) + " packets";
                                            long time_dura = (new Date().getTime()-startTime.getTime())/1000;
                                            long hour = time_dura/3600;
                                            long min = (time_dura % 3600)/60;
                                            long sec = time_dura % 60;
                                            show.time_duration = hour + " h " + min + " m " + sec + " s";

                                            Message message = new Message();
                                            message.what = 1;
                                            message.obj = show;
                                            handler.sendMessage(message);
                                        }
                                        in.close();

                                    }catch (Exception e){
                                        e.printStackTrace();
                                    }

                                }
                            }, 500, 1000);
                        }
                    };
                    vpns.start();
                }
            }
        });
    }

    //  后台获取到4over6服务器发送来的IP地址等信息，然后发送给前端
    private IpPacket getSerGatewayInfo() {
        File file = new File(ipHandleName);
        while (!file.exists()){
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        IpPacket temp_ip_packet = null;
        try {
            FileInputStream fileInputStream = new FileInputStream(file);
            BufferedInputStream in = new BufferedInputStream(fileInputStream);
            byte buf[] = new byte[1024];
            int readLen = in.read(buf, 0, 1024);
            if (readLen > 0){
                String ipDataInfo = new String(buf);
                String piece[] = ipDataInfo.split(" ");
                temp_ip_packet = new IpPacket();
                temp_ip_packet.ipAddress = piece[0];
                temp_ip_packet.route = piece[1];
                temp_ip_packet.DNS1 = piece[2];
                temp_ip_packet.DNS2 = piece[3];
                temp_ip_packet.DNS3 = piece[4];
                temp_ip_packet.socket = piece[5];

                Log.e("byrcao!", temp_ip_packet.ipAddress + ", " + temp_ip_packet.socket + ", " + temp_ip_packet.DNS1);

            }
            in.close();
        }catch (Exception e){
            System.out.println(e);
        }
        return temp_ip_packet;
    }

/*
    如果当前系统中没有VPN连接，或者存在的VPN连接不是本程序建立的，则VpnService.prepare函数会返回一个intent。
    这个intent就是用来触发确认对话框的，程序会接着调用startActivityForResult将对话框弹出来等用户确认。
    如果用户确认了，则会关闭前面已经建立的VPN连接，并重置虚拟端口。该对话框返回的时候，会调用onActivityResult函数，并告之用户的选择。


    如果当前系统中有VPN连接，并且这个连接就是本程序建立的，则函数会返回null，就不需要用户再确认了。
    因为用户在本程序第一次建立VPN连接的时候已经确认过了，就不要再重复确认了，直接手动调用onActivityResult函数就行了。
*/


    private void vpnConnect() {
        Intent intent = VpnService.prepare(MainActivity.this);
        if (intent != null) {
            startActivityForResult(intent, 0);
        } else {
            onActivityResult(0, RESULT_OK, null);
        }
    }

    protected void onActivityResult(int request, int result, Intent data){
        /*如果返回结果是OK的，也就是用户同意建立VPN连接，则将你写的，继承自VpnService类的服务启动起来就行了。*/


        if (result == RESULT_OK){
            myintent = new Intent(MainActivity.this, MyVpnService.class);
            myintent.putExtra("ip", sergateway.ipAddress);
            myintent.putExtra("route", sergateway.route);
            myintent.putExtra("DNS1", sergateway.DNS1);
            myintent.putExtra("DNS2", sergateway.DNS2);
            myintent.putExtra("DNS3", sergateway.DNS3);
            myintent.putExtra("socket", sergateway.socket);
            startService(myintent);
        }
    }


    //get Local Network information
    public String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface
                    .getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf
                        .getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress()) {
                        Log.v("ip address", inetAddress.getHostAddress().toString().split("%")[0]);
                        return inetAddress.getHostAddress().toString().split("%")[0];
                    }
                }
            }
        } catch (SocketException ex) {
            ex.printStackTrace();
        }
        return null;
    }






    public native String StringFromJNI() ;
    public native void connect4o6Byr();
    static {
        System.loadLibrary("connect4o6Byr");
    }
}




