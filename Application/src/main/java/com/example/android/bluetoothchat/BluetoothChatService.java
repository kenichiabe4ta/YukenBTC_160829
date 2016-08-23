/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.android.bluetoothchat;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import com.example.android.common.logger.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
/**
 * This class does all the work for setting up and managing Bluetooth
 * connections with other devices. It has a thread that listens for
 * incoming connections, a thread for connecting with a device, and a
 * thread for performing data transmissions when connected.
 */
public class BluetoothChatService {
    private static final String TAG = "BluetoothChatService";    // Debugging
    private static final String NAME_SECURE = "BluetoothChatSecure";// Name for the SDP record when creating server socket
    private static final String NAME_INSECURE = "BluetoothChatInsecure";
    // Unique UUID for this application
    private static final UUID MY_UUID_SECURE =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");//SPPによる通信用(UUID修正済み)
    private static final UUID MY_UUID_INSECURE =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");//SPPによる通信用(UUID修正済み)
    // メンバ変数
    private final BluetoothAdapter mAdapter;
    private final Handler mHandler;
    private AcceptThread mSecureAcceptThread;
    private AcceptThread mInsecureAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;   // 通常動作時のスレッド！！！
    private int mState;
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device

    //コンストラクタ   mHハンドラあり
    public BluetoothChatService(Context context, Handler handler) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mHandler = handler;
    }
    //メンバ関数    mHハンドラあり
    private synchronized void setState(int state) {
        Log.d(TAG, "setState() " + mState + " -> " + state);
        mState = state;     // Give the new state to the Handler so the UI Activity can update
        mHandler.obtainMessage(Constants.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }
    public synchronized int getState() {
        return mState;
    }   //Return the current connection state.

    // AcceptThreadスタート(受信接続待機) Start the chat service
    public synchronized void start() {
        if (mConnectThread != null) {   // Cancel any thread attempting to make a connection
            mConnectThread.cancel();
            mConnectThread = null;
        }
        if (mConnectedThread != null) { // Cancel any thread currently running a connection
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        setState(STATE_LISTEN);
        if (mSecureAcceptThread == null) {  // Start the thread to listen on a BluetoothServerSocket
            mSecureAcceptThread = new AcceptThread(true);
            mSecureAcceptThread.start();
        }
        if (mInsecureAcceptThread == null) {
            mInsecureAcceptThread = new AcceptThread(false);
            mInsecureAcceptThread.start();
        }
    }
    /**Start the ConnectThread to initiate a connection to a remote device.
     * @param device The BluetoothDevice to connect
     * @param secure Socket Security type - Secure (true) , Insecure (false)
     */
    // ConnectThreadスタート(接続初期化)
    public synchronized void connect(BluetoothDevice device, boolean secure) {
        Log.d(TAG, "connect to: " + device);
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {// Cancel any thread attempting to make a connection
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }
        if (mConnectedThread != null) {// Cancel any thread currently running a connection
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        mConnectThread = new ConnectThread(device, secure);// Start the thread to connect with the given device
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }
    /**Start the ConnectedThread to begin managing a Bluetooth connection
     * @param socket The BluetoothSocket on which the connection was made
     * @param device The BluetoothDevice that has been connected
     */
    // ConnectedThread(接続後スレッド)スタート！！！！！！     mHハンドラあり
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device, final String socketType) {
        Log.d(TAG, "connected, Socket Type:" + socketType);
        if (mConnectThread != null) {// Cancel the thread that completed the connection
            mConnectThread.cancel();
            mConnectThread = null;
        }
        if (mConnectedThread != null) {// Cancel any thread currently running a connection
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        if (mSecureAcceptThread != null) {// Cancel the accept thread because we only want to connect to one device
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }
        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread.cancel();
            mInsecureAcceptThread = null;
        }
        mConnectedThread = new ConnectedThread(socket, socketType);// Start the thread to manage the connection and perform transmissions
        mConnectedThread.start();
        // Send the name of the connected device back to the UI Activity
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.DEVICE_NAME, device.getName());
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        setState(STATE_CONNECTED);
    }

    public synchronized void stop() {    // Stop all threads
        Log.d(TAG, "stop");
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }
        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread.cancel();
            mInsecureAcceptThread = null;
        }
        setState(STATE_NONE);
    }
    /**Write to the ConnectedThread in an unsynchronized manner
     * @param out The bytes to write
     * @see ConnectedThread#write(byte[])
     */
    //送信メソッド メンバ関数 ConnectedThread内のwrite(byte[])を呼び出し！！！！！
    public void write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        r.write(out);        // Perform the write unsynchronized
    }
    //Indicate that the connection attempt failed and notify the UI Activity.
    private void connectionFailed() {
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.TOAST, "Unable to connect device");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        BluetoothChatService.this.start(); // Start the service over to restart listening mode
    }
    //Indicate that the connection was lost and notify the UI Activity.
    private void connectionLost() {
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.TOAST, "Device connection was lost");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        BluetoothChatService.this.start();        // Start the service over to restart listening mode
    }
    private class AcceptThread extends Thread {    //受信接続待機時スレッド
        // The local server socket
        private final BluetoothServerSocket mmServerSocket;
        private String mSocketType;
        public AcceptThread(boolean secure) {
            BluetoothServerSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";
            // Create a new listening server socket
            try {
                if (secure) {
                    tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE,MY_UUID_SECURE);
                } else {
                    tmp = mAdapter.listenUsingInsecureRfcommWithServiceRecord(NAME_INSECURE, MY_UUID_INSECURE);
                }
            } catch (IOException e) {
                Log.e(TAG, "Socket Type: " + mSocketType + "listen() failed", e);
            }
            mmServerSocket = tmp;
        }
        public void run() {
            Log.d(TAG, "Socket Type: " + mSocketType + "BEGIN mAcceptThread" + this);
            setName("AcceptThread" + mSocketType);
            BluetoothSocket socket = null;
            while (mState != STATE_CONNECTED) {   // Listen to the server socket if we're not connected
                try {
                    socket = mmServerSocket.accept(); // This is a blocking call and will only return on a successful connection or an exception
                } catch (IOException e) {
                    Log.e(TAG, "Socket Type: " + mSocketType + "accept() failed", e);
                    break;
                }
                if (socket != null) { // If a connection was accepted
                    synchronized (BluetoothChatService.this) {
                        switch (mState) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:        // Situation normal. Start the connected thread.
                                connected(socket, socket.getRemoteDevice(),mSocketType);
                                break;
                            case STATE_NONE:
                            case STATE_CONNECTED:
                                try { // Either not ready or already connected. Terminate new socket.
                                    socket.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "Could not close unwanted socket", e);
                                }
                                break;
                        }
                    }
                }
            }
            Log.i(TAG, "END mAcceptThread, socket Type: " + mSocketType);
        }
        public void cancel() {
            Log.d(TAG, "Socket Type" + mSocketType + "cancel " + this);
            try {
                mmServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Socket Type" + mSocketType + "close() of server failed", e);
            }
        }
    }
    private class ConnectThread extends Thread {    //接続時スレッド:Threadクラス継承,runメソッド宣言,startメソッド呼び出し
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;
        private String mSocketType;
        public ConnectThread(BluetoothDevice device, boolean secure) {
            mmDevice = device;
            BluetoothSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";
            try {                                 // Get a BluetoothSocket for a connection with the given BluetoothDevice
                if (secure) {
                    tmp = device.createRfcommSocketToServiceRecord(MY_UUID_SECURE);
                } else {
                    tmp = device.createInsecureRfcommSocketToServiceRecord(MY_UUID_INSECURE);
                }
            } catch (IOException e) {
                Log.e(TAG, "Socket Type: " + mSocketType + "create() failed", e);
            }
            mmSocket = tmp;
        }
        public void run() {
            Log.i(TAG, "BEGIN mConnectThread SocketType:" + mSocketType);
            setName("ConnectThread" + mSocketType);
            mAdapter.cancelDiscovery();   // Always cancel discovery because it will slow down a connection
            try {            // Make a connection to the BluetoothSocket
                // This is a blocking call and will only return on a
                // successful connection or an exception
                mmSocket.connect();
            } catch (IOException e) {
                try {
                    mmSocket.close();// Close the socket
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() " + mSocketType + " socket during connection failure", e2);
                }
                connectionFailed();
                return;
            }
            synchronized (BluetoothChatService.this) {    // Reset the ConnectThread because we're done
                mConnectThread = null;
            }
            connected(mmSocket, mmDevice, mSocketType);  // Start the connected thread
        }
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect " + mSocketType + " socket failed", e);
            }
        }
    }
    private class ConnectedThread extends Thread {    //接続後スレッド:Threadクラス継承,runメソッド宣言,startメソッド呼び出し
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        public ConnectedThread(BluetoothSocket socket, String socketType) {     //コンストラクタ
            Log.d(TAG, "create ConnectedThread: " + socketType);
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {       // Get the BluetoothSocket input and output streams
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }
            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }
        public void run() {                         //要バグ修正？？？
            Log.i(TAG, "BEGIN mConnectedThread");
            int bytes;
            byte[] buffer0 = new byte[128];
            byte[] buffer1 = new byte[1024];
            int line_end = 0;
            int index1 = 0;
            while (true) {  // Keep listening to the InputStream while connected
                try {
                    bytes = mmInStream.read(buffer0);// Read from the InputStream
                    // データを読んだ後、CRかLFコードが来るまでbuffer1に追加する。
                    for (int index0=0; index0 < bytes; index0++, index1++) {
                        buffer1[index1] = buffer0[index0];
                        if ((buffer0[index0] == 0x0a) || (buffer0[index0] == 0x0d)) {
                            line_end = 1;
                            break;
                        }
                    }
                    if (line_end == 1) {            // CRかLFコードが来たので長さindex1でbuffer1をUIへ送る
                        mHandler.obtainMessage(Constants.MESSAGE_READ, index1, -1, buffer1) // Send the obtained bytes to the UI Activity
                                .sendToTarget();    //↑BluetoothChat.MESSAGE_READではエラー
                        line_end = 0;
                        index1 = 0;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    connectionLost();
                    BluetoothChatService.this.start();// Start the service over to restart listening mode
                    break;
                }
            }
        }
        // ！！！バイト書き込み メンバ関数！！！
        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);// Share the sent message back to the UI Activity
                mHandler.obtainMessage(Constants.MESSAGE_WRITE, -1, -1, buffer).sendToTarget();
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
}