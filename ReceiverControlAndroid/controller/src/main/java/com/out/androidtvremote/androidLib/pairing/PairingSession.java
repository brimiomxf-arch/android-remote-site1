package com.out.androidtvremote.androidLib.pairing;

import com.out.androidtvremote.androidLib.AndroidRemoteContext;
import com.out.androidtvremote.androidLib.exception.PairingException;
import com.out.androidtvremote.androidLib.ssl.DummyTrustManager;
import com.out.androidtvremote.androidLib.ssl.KeyStoreManager;
import com.out.androidtvremote.androidLib.util.Utils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

public class PairingSession {

    private final BlockingQueue<Pairingmessage.PairingMessage> mMessagesQueue;

    private final PairingMessageManager mPairingMessageManager;

    SecretProvider secretProvider;

    private SSLSocket mSslSocket;

    private PairingPacketParser pairingPacketParser;

    public PairingSession() {
        mMessagesQueue = new LinkedBlockingDeque<>();
        mPairingMessageManager = new PairingMessageManager();
    }

    public void pair(String host, int port, PairingListener pairingListener)
            throws GeneralSecurityException, IOException, InterruptedException, PairingException {

        SSLContext sSLContext = SSLContext.getInstance("TLS");
        sSLContext.init(new KeyStoreManager().getKeyManagers(), new TrustManager[] { new DummyTrustManager() },
                new SecureRandom());
        SSLSocketFactory sslsocketfactory = sSLContext.getSocketFactory();
        SSLSocket sSLSocket = (SSLSocket) sslsocketfactory.createSocket();
        sSLSocket.connect(new InetSocketAddress(host, port), 4500);
        // The TV waits for the user to enter its on-screen code during pairing.
        sSLSocket.setSoTimeout(120_000);
        mSslSocket = sSLSocket;
        // sSLSocket.setNeedClientAuth(true);
        // sSLSocket.setUseClientMode(true);
        // sSLSocket.setKeepAlive(true);
        // sSLSocket.setTcpNoDelay(true);
        // sSLSocket.startHandshake();

        pairingListener.onSessionCreated();
        pairingPacketParser = new PairingPacketParser(sSLSocket.getInputStream(), mMessagesQueue);
        pairingPacketParser.start();

        final OutputStream outputStream = sSLSocket.getOutputStream();

        byte[] pairingMessage = mPairingMessageManager.createPairingMessage(
                AndroidRemoteContext.getInstance().getClientName(),
                AndroidRemoteContext.getInstance().getServiceName());
        outputStream.write(pairingMessage);
        Pairingmessage.PairingMessage pairingMessageResponse = waitForMessage();
        logReceivedMessage(pairingMessageResponse.toString());

        byte[] pairingOption = new PairingMessageManager().createPairingOption();
        outputStream.write(pairingOption);
        Pairingmessage.PairingMessage pairingOptionAck = waitForMessage();
        logReceivedMessage(pairingOptionAck.toString());

        byte[] configMessage = new PairingMessageManager().createConfigMessage();
        outputStream.write(configMessage);
        Pairingmessage.PairingMessage pairingConfigAck = waitForMessage();
        logReceivedMessage(pairingConfigAck.toString());

        if (secretProvider != null)
            secretProvider.requestSecret(this);
        pairingListener.onSecretRequested();
        Pairingmessage.PairingMessage pairingSecretMessage = waitForMessage();
        byte[] secretMessage = mPairingMessageManager.createSecretMessage(pairingSecretMessage);
        outputStream.write(secretMessage);
        Pairingmessage.PairingMessage pairingSecretAck = waitForMessage();
        logReceivedMessage(pairingSecretAck.toString());

        pairingListener.onPaired();
        pairingListener.onSessionEnded();

    }

    Pairingmessage.PairingMessage waitForMessage() throws InterruptedException, PairingException {
        Pairingmessage.PairingMessage pairingMessage = mMessagesQueue.take();
        if (pairingMessage.getStatus() != Pairingmessage.PairingMessage.Status.STATUS_OK) {
            throw new PairingException(pairingMessage.toString());
        }
        return pairingMessage;
    }

    public void provideSecret(String secret) {
        createCodeSecret(secret);
    }

    private void createCodeSecret(String code) {
        if (code == null || !code.matches("(?i)[0-9a-f]{6}")) {
            throw new IllegalArgumentException("رمز Android TV يجب أن يتكون من 6 خانات hex.");
        }
        PairingChallengeResponse pairingChallengeResponse = new PairingChallengeResponse(
                Utils.getLocalCert(mSslSocket.getSession()), Utils.getPeerCert(mSslSocket.getSession()));
        // The six hex symbols are one check byte followed by a two-byte nonce.
        // Hash the certificates and nonce, then compare the first hash byte with
        // the first two symbols. The old checkGamma path treated part of the
        // nonce as a second checksum and rejected valid TV codes.
        byte[] expectedCheck = Utils.hexStringToBytes(code.substring(0, 2));
        byte[] nonce = Utils.hexStringToBytes(code.substring(2));
        byte[] pairingSecret;
        try {
            pairingSecret = pairingChallengeResponse.getAlpha(nonce);
        } catch (PairingException e) {
            throw new RuntimeException(e);
        }
        if (pairingSecret.length == 0 || pairingSecret[0] != expectedCheck[0]) {
            throw new IllegalArgumentException("رمز الاقتران غير صحيح أو انتهت صلاحيته. أدخل الرمز الحالي الظاهر على التلفاز.");
        }
        Pairingmessage.PairingMessage secretMessageProto = new PairingMessageManager()
                .createSecretMessageProto(pairingSecret);
        try {
            mMessagesQueue.put(secretMessageProto);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    void logSendMessage(String message) {

    }

    public void abort() {
        if (pairingPacketParser != null)
            pairingPacketParser.abort();

        // Close the socket to notify TV that pairing is cancelled
        // Must be done on background thread to avoid NetworkOnMainThreadException
        if (mSslSocket != null) {
            final SSLSocket socketToClose = mSslSocket;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        socketToClose.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        }
    }

    void logReceivedMessage(String message) {

    }

}
