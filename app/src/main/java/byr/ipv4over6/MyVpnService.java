package byr.ipv4over6;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * Created by byr on 5/15/2017.
 */
public class MyVpnService extends VpnService {

    //    Configure a builder for the interface.
    Builder builder = new Builder();
    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    final String ipHandleName = "/data/data/byr.ipv4over6/myfifo";
    private String ipAddress, route, dns, socket;
    /*
        There are two function must be implemented when extending the Service class,
        the onStartCommand and the onDestroy.
        Usually a Thread can be used to provide the service in background,
        which can be start or interrupted/destroyed in above functions.
    */
    @Override
    public int onStartCommand(final Intent intent, int flags, int startId){
//        mThread = new Thread(new Runnable() {
//            @Override
//            public void run() {
//                if (ipAddress == null){
//                    ipAddress = intent.getStringExtra("ip");
//                    route = intent.getStringExtra("route");
//                    dns = intent.getStringExtra("DNS2");
//                    socket = intent.getStringExtra("socket");
//                }
//                try {
//                    Log.e("byryes", ipAddress + ", " + route + ", " + dns + ", " + socket + ", ");
//                    protect(Integer.parseInt(socket));
//                /*
//                    builder.setMtu(...);
//                    builder.addAddress(...);
//                    builder.addRoute(...);
//                    builder.addDnsServer(...);
//                    builder.addSearchDomain(...);
//                    builder.setSession(...);
//                    builder.setConfigureIntent(...);
//                    1）MTU（Maximun Transmission Unit），即表示虚拟网络端口的最大传输单元，如果发送的包长度超过这个数字，则会被分包；
//                    2）Address，即这个虚拟网络端口的IP地址；
//                    3）Route，只有匹配上的IP包，才会被路由到虚拟端口上去。如果是0.0.0.0/0的话，则会将所有的IP包都路由到虚拟端口上去；
//                    4）DNS Server，就是该端口的DNS服务器地址；
//                    5）Search Domain，就是添加DNS域名的自动补齐。DNS服务器必须通过全域名进行搜索，但每次查找都输入全域名太麻烦了，可以通过配置域名的自动补齐规则予以简化；
//                    6）Session，就是你要建立的VPN连接的名字，它将会在系统管理的与VPN连接相关的通知栏和对话框中显示出来；
//                    7）Configure Intent，这个intent指向一个配置页面，用来配置VPN链接。它不是必须的，如果没设置的话，则系统弹出的VPN相关对话框中不会出现配置按钮。
//                    最后调用Builder.establish函数，如果一切正常的话，tun0虚拟网络接口就建立完成了。并且，同时还会通过iptables命令，修改NAT表，将所有数据转发到tun0接口上。
//                */
//                    // Configure the TUN and get the interface.
//                    mInterface = builder.setSession("ByrVPNService")
//                            .addAddress(ipAddress, 24)
//                            .addDnsServer(dns)
//                            .addRoute(route, 0)
//                            .setMtu(1000)
//                            .establish();
//
//                    /*
//                    通过读写VpnService.Builder返回的ParcelFileDescriptor实例来获得设备上
//                    所有向外发送的IP数据包和返回处理过后的IP数据包到TCP/IP协议栈
//                    ParcelFileDescriptor类有一个getFileDescriptor函数，其会返回一个文件描述符，
//                    这样就可以将对接口的读写操作转换成对文件的读写操作。
//                    每次调用FileInputStream.read函数会读取一个IP数据包，而调用FileOutputStream.write函数
//                    会写入一个IP数据包到TCP/IP协议栈。
//                     */
//                    File file = new File(ipHandleName);
//                    FileOutputStream fos = new FileOutputStream(file);
//                    BufferedOutputStream out = new BufferedOutputStream(fos);
//                    int fd = mInterface.getFd();
//                    ByteArrayOutputStream boutput = new ByteArrayOutputStream();
//                    DataOutputStream doutput = new DataOutputStream(boutput);
//                    doutput.writeInt(fd);
//                    byte[] buf = boutput.toByteArray();
//                    //将虚拟接口tun0的文件描述符通过管道发给后台
//                    out.write(buf, 0, buf.length);
//                    out.flush();
//                    out.close();
//
//
//                    /*// Packets to be sent are queued in this input stream.
//                    FileInputStream in = new FileInputStream(mInterface.getFileDescriptor());
//                    // Packets received need to be written to this output stream.
//                    FileOutputStream out = new FileOutputStream(mInterface.getFileDescriptor());
//                    String helloworld = "helloByr";
//                    byte[] buf = helloworld.getBytes();
//                    out.write(buf, 0, buf.length);*/
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//            }
//        }, "ByrVpnRunnable");
//
//        mThread.start();


        if (ipAddress == null){
            ipAddress = intent.getStringExtra("ip");
            route = intent.getStringExtra("route");
            dns = intent.getStringExtra("DNS2");
            socket = intent.getStringExtra("socket");
        }
        try {
            Log.e("byryes", ipAddress + ", " + route + ", " + dns + ", " + socket + ", ");
            protect(Integer.parseInt(socket));

            mInterface = builder.setSession("ByrVPNService")
                    .addAddress(ipAddress, 24)
                    .addDnsServer(dns)
                    .addRoute(route, 0)
                    .setMtu(1000)
                    .establish();
            File file = new File(ipHandleName);
            FileOutputStream fos = new FileOutputStream(file);
            BufferedOutputStream out = new BufferedOutputStream(fos);
            int fd = mInterface.getFd();
            ByteArrayOutputStream boutput = new ByteArrayOutputStream();
            DataOutputStream doutput = new DataOutputStream(boutput);
            doutput.writeInt(fd);
            byte[] buf = boutput.toByteArray();
            //将虚拟接口tun0的文件描述符通过管道发给后台
            out.write(buf, 0, buf.length);
            out.flush();
            out.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
        return START_STICKY;
    }


    @Override
    public void onDestroy() {
        // TODO Auto-generated method stub
        if (mThread != null) {
            mThread.interrupt();
        }
        super.onDestroy();
    }
}
