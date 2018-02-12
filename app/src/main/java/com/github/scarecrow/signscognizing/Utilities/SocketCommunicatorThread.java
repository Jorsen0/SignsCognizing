package com.github.scarecrow.signscognizing.Utilities;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Scanner;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/**
 * Created by Scarecrow on 2018/2/8.
 */

public class SocketCommunicatorThread extends HandlerThread {

    private static final MediaType MEDIA_TYPE_JSON
            = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8");
    private static String TAG = "SocketCommunicator";

    private Handler main_thread_handler,
            communicator_handler;

    private int port;

    private Socket client_sock;

    private Thread listener_thread;

    private String HOST_IP = "192.168.0.102";

    public SocketCommunicatorThread(Handler main_thread_handler) {
        super(TAG);
        this.main_thread_handler = main_thread_handler;
    }

    public void startConnection() {
        communicator_handler.obtainMessage(SocketConnectionManager.TASK_BUILD_UP_CONNECTION)
                .sendToTarget();
    }

    public void sendMessage(String message) {
        communicator_handler.obtainMessage(SocketConnectionManager.TASK_SEND_INFO, message)
                .sendToTarget();
    }

    public void disconnect() {
        listener_thread.interrupt();
        try {
            client_sock.getOutputStream().close();
            client_sock.getInputStream().close();
            client_sock.close();

        } catch (Exception ee) {
            Log.e(TAG, "disconnect: error: " + ee);
            ee.printStackTrace();
        }
    }

    @Override
    @SuppressLint("HandlerLeak")
    protected void onLooperPrepared() {
        communicator_handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case SocketConnectionManager.TASK_BUILD_UP_CONNECTION:
                        buildUpSocketConnection();
                        break;

                    case SocketConnectionManager.TASK_SEND_INFO:
                        String info = (String) msg.obj;
                        socketSend(info);
                        break;
                }
            }
        };

        main_thread_handler.obtainMessage(SocketConnectionManager.LOOPER_READY)
                .sendToTarget();
    }


    private int getTargetPort() throws Exception {

        OkHttpClient okHttpClient = new OkHttpClient();

        String param = "armband_id",
                armband_id = ArmbandManager.getArmbandsManger()
                        .getCurrentConnectedArmband()
                        .getArmband_id();

        RequestBody requestBody = RequestBody
                .create(MEDIA_TYPE_JSON, "?" + param + "=" + armband_id);
        // 这里非常奇怪 必须要在第一个参数名前面加上 ? 才能使django接受post内容
        // 然后在进入请求处理方法时 这个问号居然还在参数名上
        Request request = new Request.Builder()
                .url(ArmbandManager.SERVER_IP_ADDRESS + "/request_socket_connection/")
                .post(requestBody)
                .build();

        Response response = okHttpClient.newCall(request).execute();
        if (response.isSuccessful()) {
            return Integer.valueOf(response.body().string());
        } else {
            Log.e(TAG, "handleMessage: cannot open a socket on server");
            throw new Exception("handleMessage: cannot open a socket on server");
        }
    }

    private void buildUpSocketConnection() {
        try {
            //这里进行套接字的连接建立工作
            port = getTargetPort();
            client_sock = new Socket(HOST_IP, port);

            // 再开一个线程专门用来监听 它收到消息了就就先缓存着 直到消息结尾
            // 然后将整个消息返回给主线程 然后再返回等待状态
            listener_thread = new Thread(new ListenerLoop
                    (client_sock, main_thread_handler));
            listener_thread.start();

        } catch (Exception ee) {
            main_thread_handler.obtainMessage(SocketConnectionManager.CONNECT_FALIED)
                    .sendToTarget();
            Log.e(TAG, "handleMessage: onEstablish connection : " + ee);
            ee.printStackTrace();
            return;
        }
        main_thread_handler.obtainMessage(SocketConnectionManager.CONNECT_SUCCESS)
                .sendToTarget();
    }

    private void socketSend(String info) {
        Log.d(TAG, "handleMessage: seed info : " + info);
        try {
            OutputStream outputStream = client_sock.getOutputStream();
            outputStream.write(info.getBytes(Charset.forName("utf-8")));
            outputStream.flush();
        } catch (Exception ee) {
            Log.e(TAG, "handleMessage: error in send info : " + ee);
            ee.printStackTrace();
        }
    }


    /**
     * 套接字的监听循环
     * 放在一个thread里 每当收到消息 向外界的handler发送消息
     */
    private class ListenerLoop implements Runnable {
        private InputStream inputStream;
        private Handler callback_thread;

        public ListenerLoop(Socket client_sock, Handler outer_thread) {
            callback_thread = outer_thread;
            try {
                inputStream = client_sock.getInputStream();
            } catch (Exception ee) {
                Log.e(TAG, "ListenerLoop: error in get listener loop" + ee);
            }
        }

        @Override
        public void run() {
            Scanner scanner = new Scanner(inputStream);
            while (!Thread.currentThread().isInterrupted()) {
                if (scanner.hasNext()) {
                    StringBuilder buffer = new StringBuilder();
                    while (scanner.hasNext()) {
                        buffer.append(scanner.nextLine());
                    }
                    String text = buffer.toString();
                    callback_thread.obtainMessage(SocketConnectionManager.RECEIVE_MESSAGE, text)
                            .sendToTarget();
                }
            }
            try {
                inputStream.close();
            } catch (Exception ee) {
                Log.e(TAG, "run: in receive text " + ee);
            }


        }
    }

}
