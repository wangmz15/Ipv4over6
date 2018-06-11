#include "byr_ipv4over6_MainActivity.h"

#include<android/log.h>

typedef enum { false = 0, true } bool;
#define TAG "JNI" // 这个是自定义的LOG的标识
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,TAG ,__VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,TAG ,__VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,TAG ,__VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,TAG ,__VA_ARGS__)
#define LOGF(...) __android_log_print(ANDROID_LOG_FATAL,TAG ,__VA_ARGS__)








#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <pthread.h>
#include <errno.h>
#include <sys/socket.h>
#include <netdb.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <semaphore.h>

typedef struct {
    int length;
    char type;
    char data[2048];
} Message;
#define QN 500
Message socket_w_que[QN];
Message vpn_w_que[QN];

typedef struct
{
    int swhead, swtail;
} HtSocket;
HtSocket htsocket, htvpn;
#define SERVER_ADDR "2001:df1:801:a004:6::3d21"
#define SERVER_PORT 1313
//#define SERVER_ADDR "2402:f000:1:4417::900"
//#define SERVER_PORT 5678
#define MAX_BUFFER 4000

#define MSGTYPE_IP_REQ 100
#define MSGTYPE_IP_REC 101
#define MSGTYPE_DATA_SEND 102
#define MSGTYPE_DATA_RECV 103
#define MSGTYPE_HEARTBEAT 104


#define CHK(expr)  do { if ( (expr) == -1 ) { LOGF("(line %d): ERROR - %s.\n", __LINE__, strerror( errno ) ); exit( 1 );  } } while ( false )

int heartbeat_send_counter = 0;
int heartbeat_recv_time = 0;
//  进出 data长度 package个数
int out_length = 0;
int out_times = 0;
int in_length = 0;
int in_times = 0;


int client_socket;
//  虚接口
int vpn_handle;
//  传递虚接口的管道
int fifo_handle;
//  传递 in_length out_length in_times out_times 的管道
int fifo_handle_stats;

//  是否关闭4over6程序
bool isClosed = false;
//  是否获取到服务器IP
bool hasIP = false;

//  in_length out_length in_times out_times
pthread_mutex_t traffic_mutex_in;
pthread_mutex_t traffic_mutex_out;

pthread_mutex_t ht_vpn_mutex;
pthread_mutex_t ht_socket_mutex;
//  消费生产信号量
//  full 表示生产了多少
//  empty 表示有多少空闲
//  sem_wait函数 P操作
//  sem_post函数 V操作
sem_t socket_empty, socket_full, vpn_empty, vpn_full;
int socket_empty_res, socket_full_res, vpn_empty_res, vpn_full_res;


//  心跳包计时器
void* timer(void* nouse) {
    // Initialize Heart beat Message
    Message heart_beat;
    heart_beat.length = 4;
    heart_beat.type = MSGTYPE_HEARTBEAT;
    // char buffer[MAX_BUFFER+1];
    char fifo_buffer[MAX_BUFFER+1];

    while(!isClosed) {
        // First Checkout Heart Beat Time
        int current_time = time((time_t*)NULL);
        if(current_time - heartbeat_recv_time >= 60) {
            // We Lost Server
            // isClosed = true;
            LOGF("We Lost Server!!!\n");
            exit(1);
        }
        if(heartbeat_send_counter == 0) {
            // Send Heart Beat Package
            int len;
            CHK(len= send(client_socket, &heart_beat, 5, 0));
            if(len != 5) {
                LOGF("Something happened while sending heart beats!\n");
            }
            heartbeat_send_counter = 19;
        }
        else {
            heartbeat_send_counter--;
            LOGE("heartbeat is decreasing!\n");
        }
        // Send Stats
        bzero(fifo_buffer, 100);
        sprintf(fifo_buffer, "%d %d %d %d ", out_length, out_times, in_length, in_times);
        CHK(write(fifo_handle_stats, fifo_buffer, strlen(fifo_buffer) + 1));

        // Clear Stats
        pthread_mutex_lock(&traffic_mutex_out);
        out_length = 0;
        out_times = 0;
        pthread_mutex_unlock(&traffic_mutex_out);
        pthread_mutex_lock(&traffic_mutex_in);
        in_length = 0;
        in_times = 0;
        pthread_mutex_unlock(&traffic_mutex_in);
        sleep(1);
    }
    LOGD("timer thread exit \n");
}

//  从手机客户端发出去的数据全部发给虚接口
//  然后vpn后台程序(即本程序)从虚接口读出,再发送到服务器
void* vpnService(void* nouse) {
    char buffer[MAX_BUFFER+1];
    int len, maxfdp = vpn_handle + 1;
    fd_set fds;

    while(!isClosed) {
        FD_ZERO(&fds);
        FD_SET(vpn_handle ,&fds);
        switch (select(maxfdp, &fds, NULL, NULL, NULL)) {
            case -1:
                LOGF("(line %d): ERROR - %s.\n", __LINE__, strerror( errno ) );
                exit(1);
            case 0:
                break;
            default:
                if(FD_ISSET(vpn_handle, &fds)) {
                    sem_wait(&socket_empty);
                    pthread_mutex_lock(&ht_socket_mutex);

                    CHK(len = read(vpn_handle, buffer, MAX_BUFFER));
                    LOGE("Send %d Bytes to Server", len);
                    socket_w_que[htsocket.swtail].length = len + 5;
                    socket_w_que[htsocket.swtail].type = MSGTYPE_DATA_SEND;
                    memcpy(socket_w_que[htsocket.swtail].data, buffer, len);
                    htsocket.swtail = (htsocket.swtail + 1) % QN;

                    pthread_mutex_unlock(&ht_socket_mutex);
                    sem_post(&socket_full);
                }
        }
    }
    LOGD("vpn_handle thread exit \n");
}

void* waitExit(void* nouse) {
    char buffer[MAX_BUFFER+1];
    while(!isClosed) {
        int len = read(fifo_handle, buffer, MAX_BUFFER);
        if(buffer[0] == '9' && buffer[1] == '9' && buffer[2] == '9') {
            isClosed = true;
        }
    }
}


void* socket_send_fun(void* nouse){
    char buffer[MAX_BUFFER+1];
    while (!isClosed){
        sem_wait(&socket_full);
        pthread_mutex_lock(&ht_socket_mutex);

        htsocket.swhead = (htsocket.swhead + 1) % QN;
        int len = send(client_socket, &socket_w_que[htsocket.swhead], socket_w_que[htsocket.swhead].length, 0);
        if(len != socket_w_que[htsocket.swhead].length) {
            LOGD("Send Error!\n");
        }
        pthread_mutex_lock(&traffic_mutex_out);
        out_length += socket_w_que[htsocket.swhead].length - 5;
        out_times ++;
        pthread_mutex_unlock(&traffic_mutex_out);

        pthread_mutex_unlock(&ht_socket_mutex);
        sem_post(&socket_empty);

    }
}


void* vpn_send_fun(void* nouse){
    while (!isClosed){
        sem_wait(&vpn_full);
        pthread_mutex_lock(&ht_vpn_mutex);

        htvpn.swhead = (htvpn.swhead + 1) % QN;
        int should = vpn_w_que[htvpn.swhead].length - 5;
        int len = write(vpn_handle, vpn_w_que[htvpn.swhead].data, should);

        if (len != should) {
            LOGD("Error!\n");
        }
        // Do Stats
        pthread_mutex_lock(&traffic_mutex_in);
        in_times ++;
        in_length += len;
        pthread_mutex_unlock(&traffic_mutex_in);

        pthread_mutex_unlock(&ht_vpn_mutex);
        sem_post(&vpn_empty);
    }
}


int main(void) {
    isClosed = false;
    hasIP = false;
    heartbeat_send_counter = 0;
    heartbeat_recv_time = 0;
    out_length = 0;
    out_times = 0;
    in_length = 0;
    in_times = 0;
    //  队列头尾 左开右开
    htsocket.swhead = -1;
    htsocket.swtail = 0;
    htvpn.swhead = -1;
    htvpn.swtail = 0;

    //  信号量初始化
    socket_full_res = sem_init(&socket_full, 0, 0);
    socket_empty_res = sem_init(&socket_empty, 0, QN);
    vpn_full_res = sem_init(&vpn_full, 0, 0);
    vpn_empty_res = sem_init(&vpn_empty, 0, QN);
    if(socket_empty_res == -1 || socket_full_res == -1
       || vpn_empty_res == -1 || vpn_full_res == -1)
    {
        perror("semaphore intitialization failed\n");
        exit(-1);
    }


    //  初始化互斥量
    pthread_mutex_init(&traffic_mutex_in, NULL);
    pthread_mutex_init(&traffic_mutex_out, NULL);
    pthread_mutex_init(&ht_vpn_mutex, NULL);
    pthread_mutex_init(&ht_socket_mutex, NULL);


    const char * fifo_name = "/data/data/byr.ipv4over6/myfifo";
    const char * fifo_name_stats = "/data/data/byr.ipv4over6/myfifo_stats";

    /* create the FIFO (named pipe) */
    mkfifo(fifo_name, 0666);
    mkfifo(fifo_name_stats, 0666);
    CHK(fifo_handle = open(fifo_name, O_RDWR|O_CREAT|O_TRUNC));
    CHK(fifo_handle_stats = open(fifo_name_stats, O_RDWR|O_CREAT|O_TRUNC));



    struct sockaddr_in6 server_socket;
    CHK(client_socket = socket(AF_INET6, SOCK_STREAM, 0));

    LOGD("socket created!\n");

    bzero(&server_socket, sizeof(server_socket));
    server_socket.sin6_family = AF_INET6;
    server_socket.sin6_port = htons(SERVER_PORT);
    CHK(inet_pton(AF_INET6, SERVER_ADDR, &server_socket.sin6_addr));
    LOGD("address created!\n");

    CHK(connect(client_socket, (struct sockaddr *) &server_socket, sizeof(server_socket)));
    LOGD("Connect Succeeded!\n");

    // Initialize Receive Time
    heartbeat_recv_time = time((time_t*)NULL);
    pthread_t timer_thread;
    //  创建定时器线程
    pthread_create(&timer_thread, NULL, timer, NULL);


    // 发送消息类型为100的IP请求消息
    Message msg;
    bzero(&msg, sizeof(Message));
    msg.length = sizeof(Message);
    //  TYPE 100
    msg.type = MSGTYPE_IP_REQ;
    char buffer[MAX_BUFFER+1];
    bzero(buffer, MAX_BUFFER+1);
    memcpy(buffer, &msg, sizeof(Message));
    CHK(send(client_socket, buffer, 5, 0));
    int len, len2;
    while(!isClosed) {
        // Now Receive Package

        bzero(buffer, MAX_BUFFER + 1);
        //len = 0;
        //while(len != 4){
        //    int l = recv(client_socket, buffer+len, 4 - len, 0);
        //    len += l;
        //    printf("recv len:%d\n", l);
        //}
        //int sz = *(int*) buffer - 4;
        //int i = 0;
        //for(i = 0; i < sz; ++i) {
        //    CHK(len2 = recv(client_socket, buffer+4+i, 1, 0));
        //}

        // Now Parse Package
        //bzero(&msg, sizeof(Message));
        //memcpy(&msg, buffer, sizeof(Message));

        int already = 0;
        while (already < 4){
            CHK(len = recv(client_socket, buffer + already, 4 - already, 0));
            already += len;
        }
        int sz = *(int*) buffer;
        if(sz == 0) {
            sz = 5;
        }
        //  粘包处理
        while (already < sz){
            CHK(len2 = recv(client_socket, buffer + already, sz - already, 0));
            already += len2;
        }

        LOGE("Receive %d Bytes From Server!\n", sz);

        // Now Parse Package
        bzero(&msg, sizeof(Message));
        memcpy(&msg, buffer, sz);
        LOGE("message type:%c\n", msg.type);
        LOGE("message data:%s\n", msg.data);
        //  TYPE 101
        if(!hasIP && msg.type == MSGTYPE_IP_REC) {
            LOGD("Type: IP_REC\nContents: %s\n", msg.data);
            char b[1024] = "";
            bzero(b, sizeof(b));
            sprintf(b, "%s %d \0", msg.data, client_socket);
            len = strlen(b) + 1;
            int size = write(fifo_handle, b, len);
            if(len != size) {
                fprintf( stderr, "(line %d): ERROR - %s.\n", __LINE__, strerror( errno ) );
                exit(1);
            }

            sleep(1);

            // Now Wait for File Handle
            len = read(fifo_handle, buffer, MAX_BUFFER);
            if(len != sizeof(int)) {
                LOGD("File Handle Read Error! Read %s (len:%d)\n", buffer, len);
                exit(1);
            }
            vpn_handle = *(int*)buffer;
            vpn_handle = ntohl(vpn_handle);
            LOGD("Get VPN Handle %d Succeeded!\n", vpn_handle);
            // Create a new thread for VPN service
            pthread_t vpn_thread, exit_thread, socket_send_fun_thread, vpn_send_fun_thread;
            pthread_create(&vpn_thread, NULL, vpnService, NULL);
            pthread_create(&socket_send_fun_thread, NULL, socket_send_fun, NULL);
            pthread_create(&vpn_send_fun_thread, NULL, vpn_send_fun, NULL);
            pthread_create(&exit_thread, NULL, waitExit, NULL);

            hasIP = true;
        }
            //  TYPE 103
            //  从外面发送给手机客户端的数据都先发送到vpn后台程序(即本程序)
            //  然后通过转发给虚接口转发到手机的客户端
        else if(msg.type == MSGTYPE_DATA_RECV) {
            LOGF("Type: DATA_REC (length: %d)\nContents: %s\n", msg.length, msg.data);

            sem_wait(&vpn_empty);
            pthread_mutex_lock(&ht_vpn_mutex);

            memcpy(&vpn_w_que[htvpn.swtail], &msg, msg.length);
            htvpn.swtail = (htvpn.swtail + 1) % QN;

            pthread_mutex_unlock(&ht_vpn_mutex);
            sem_post(&vpn_full);

/*            len = write(vpn_handle, msg.data, msg.length-5);
            if (len != msg.length-5) {
                LOGD("Error!\n");
            }

            // Do Stats
            pthread_mutex_lock(&traffic_mutex_in);
            in_times ++;
            in_length += len;
            pthread_mutex_unlock(&traffic_mutex_in);
*/
        }
            //  TYPE 104
        else if(msg.type == MSGTYPE_HEARTBEAT) {
            LOGE("Type: HEARTBEAT\nContents: %s\n", msg.data);
            heartbeat_recv_time = time((time_t*)NULL);
        }
        else {
            LOGD("Unknown Receive Type %d!\n", msg.type);
            LOGD("Contents: %s\n", msg.data);
        }
    }
    CHK(close(client_socket));
    CHK(close(vpn_handle));
    CHK(close(fifo_handle));
    CHK(close(fifo_handle_stats));
    CHK(pthread_mutex_destroy(&traffic_mutex_in));
    CHK(pthread_mutex_destroy(&traffic_mutex_out));
    CHK(pthread_mutex_destroy(&ht_socket_mutex));
    CHK(pthread_mutex_destroy(&ht_vpn_mutex));

    return EXIT_SUCCESS;
}























JNIEXPORT jstring JNICALL Java_com_example_maye_IVI_MainActivity_StringFromJNI(JNIEnv *env, jobject this)
{
    return (*env)->NewStringUTF(env, "Hello World From JNI!");
}




JNIEXPORT void JNICALL Java_byr_ipv4over6_MainActivity_connect4o6Byr(JNIEnv *env, jobject this){
    LOGD("VPN thread Starts!");
    main();
//    close(client_socket);

    LOGD("VPN thread Ends!");
}