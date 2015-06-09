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

package com.android.mms.service;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Telephony;
import android.service.carrier.CarrierMessagingService;
import android.service.carrier.ICarrierMessagingService;
import android.telephony.CarrierMessagingServiceManager;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.SmsApplication;
import com.android.mms.service.exception.MmsHttpException;

import com.google.android.mms.MmsException;
import com.google.android.mms.pdu.GenericPdu;
import com.google.android.mms.pdu.PduHeaders;
import com.google.android.mms.pdu.PduParser;
import com.google.android.mms.pdu.PduPersister;
import com.google.android.mms.pdu.SendConf;
import com.google.android.mms.pdu.SendReq;
import com.google.android.mms.util.SqliteWrapper;

import java.util.List;

/**
 * Request to send an MMS
 */
public class SendRequest extends MmsRequest {
    private final Uri mPduUri;
    private byte[] mPduData;
    private final String mLocationUrl;
    private final PendingIntent mSentIntent;

    public SendRequest(RequestManager manager, int subId, Uri contentUri, String locationUrl,
            PendingIntent sentIntent, String creator, Bundle configOverrides, Context context) {
        super(manager, subId, creator, configOverrides, context);
        mPduUri = contentUri;
        mPduData = null;
        mLocationUrl = locationUrl;
        mSentIntent = sentIntent;
    }

    @Override
    protected byte[] doHttp(Context context, MmsNetworkManager netMgr, ApnSettings apn)
            throws MmsHttpException {
        final MmsHttpClient mmsHttpClient = netMgr.getOrCreateHttpClient();
        if (mmsHttpClient == null) {
            Log.e(MmsService.TAG, "MMS network is not ready!");
            throw new MmsHttpException(0/*statusCode*/, "MMS network is not ready");
        }
        return mmsHttpClient.execute(
                mLocationUrl != null ? mLocationUrl : apn.getMmscUrl(),
                mPduData,
                MmsHttpClient.METHOD_POST,
                apn.isProxySet(),
                apn.getProxyAddress(),
                apn.getProxyPort(),
                mMmsConfig,
                mSubId);
    }

    @Override
    protected PendingIntent getPendingIntent() {
        return mSentIntent;
    }

    @Override
    protected int getQueueType() {
        return MmsService.QUEUE_INDEX_SEND;
    }

    @Override
    protected Uri persistIfRequired(Context context, int result, byte[] response) {
        if (!SmsApplication.shouldWriteMessageForPackage(mCreator, context)) {
            // Not required to persist
            return null;
        }
        Log.d(MmsService.TAG, "SendRequest.persistIfRequired");
        if (mPduData == null) {
            Log.e(MmsService.TAG, "SendRequest.persistIfRequired: empty PDU");
            return null;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            final boolean supportContentDisposition =
                    mMmsConfig.getBoolean(SmsManager.MMS_CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION);
            // Persist the request PDU first
            GenericPdu pdu = (new PduParser(mPduData, supportContentDisposition)).parse();
            if (pdu == null) {
                Log.e(MmsService.TAG, "SendRequest.persistIfRequired: can't parse input PDU");
                return null;
            }
            if (!(pdu instanceof SendReq)) {
                Log.d(MmsService.TAG, "SendRequest.persistIfRequired: not SendReq");
                return null;
            }
            final PduPersister persister = PduPersister.getPduPersister(context);
            final Uri messageUri = persister.persist(
                    pdu,
                    Telephony.Mms.Sent.CONTENT_URI,
                    true/*createThreadId*/,
                    true/*groupMmsEnabled*/,
                    null/*preOpenedFiles*/);
            if (messageUri == null) {
                Log.e(MmsService.TAG, "SendRequest.persistIfRequired: can not persist message");
                return null;
            }
            // Update the additional columns based on the send result
            final ContentValues values = new ContentValues();
            SendConf sendConf = null;
            if (response != null && response.length > 0) {
                pdu = (new PduParser(response, supportContentDisposition)).parse();
                if (pdu != null && pdu instanceof SendConf) {
                    sendConf = (SendConf) pdu;
                }
            }
            if (result != Activity.RESULT_OK
                    || sendConf == null
                    || sendConf.getResponseStatus() != PduHeaders.RESPONSE_STATUS_OK) {
                // Since we can't persist a message directly into FAILED box,
                // we have to update the column after we persist it into SENT box.
                // The gap between the state change is tiny so I would not expect
                // it to cause any serious problem
                // TODO: we should add a "failed" URI for this in MmsProvider?
                values.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_FAILED);
            }
            if (sendConf != null) {
                values.put(Telephony.Mms.RESPONSE_STATUS, sendConf.getResponseStatus());
                values.put(Telephony.Mms.MESSAGE_ID,
                        PduPersister.toIsoString(sendConf.getMessageId()));
            }
            values.put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L);
            values.put(Telephony.Mms.READ, 1);
            values.put(Telephony.Mms.SEEN, 1);
            if (!TextUtils.isEmpty(mCreator)) {
                values.put(Telephony.Mms.CREATOR, mCreator);
            }
            values.put(Telephony.Mms.SUBSCRIPTION_ID, mSubId);
            if (SqliteWrapper.update(context, context.getContentResolver(), messageUri, values,
                    null/*where*/, null/*selectionArg*/) != 1) {
                Log.e(MmsService.TAG, "SendRequest.persistIfRequired: failed to update message");
            }
            return messageUri;
        } catch (MmsException e) {
            Log.e(MmsService.TAG, "SendRequest.persistIfRequired: can not persist message", e);
        } catch (RuntimeException e) {
            Log.e(MmsService.TAG, "SendRequest.persistIfRequired: unexpected parsing failure", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return null;
    }

    /**
     * Read the pdu from the file descriptor and cache pdu bytes in request
     * @return true if pdu read successfully
     */
    private boolean readPduFromContentUri() {
        if (mPduData != null) {
            return true;
        }
        final int bytesTobeRead = mMmsConfig.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE);
        mPduData = mRequestManager.readPduFromContentUri(mPduUri, bytesTobeRead);
        return (mPduData != null);
    }

    /**
     * Transfer the received response to the caller (for send requests the pdu is small and can
     *  just include bytes as extra in the "returned" intent).
     *
     * @param fillIn the intent that will be returned to the caller
     * @param response the pdu to transfer
     */
    @Override
    protected boolean transferResponse(Intent fillIn, byte[] response) {
        // SendConf pdus are always small and can be included in the intent
        if (response != null) {
            fillIn.putExtra(SmsManager.EXTRA_MMS_DATA, response);
        }
        return true;
    }

    /**
     * Read the data from the file descriptor if not yet done
     * @return whether data successfully read
     */
    @Override
    protected boolean prepareForHttpRequest() {
        return readPduFromContentUri();
    }

    /**
     * Try sending via the carrier app
     *
     * @param context the context
     * @param carrierMessagingServicePackage the carrier messaging service sending the MMS
     */
    public void trySendingByCarrierApp(Context context, String carrierMessagingServicePackage) {
        final CarrierSendManager carrierSendManger = new CarrierSendManager();
        final CarrierSendCompleteCallback sendCallback = new CarrierSendCompleteCallback(
                context, carrierSendManger);
        carrierSendManger.sendMms(context, carrierMessagingServicePackage, sendCallback);
    }

    @Override
    protected void revokeUriPermission(Context context) {
        if (mPduUri != null) {
            context.revokeUriPermission(mPduUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    /**
     * Sends the MMS through through the carrier app.
     */
    private final class CarrierSendManager extends CarrierMessagingServiceManager {
        // Initialized in sendMms
        private volatile CarrierSendCompleteCallback mCarrierSendCompleteCallback;

        void sendMms(Context context, String carrierMessagingServicePackage,
                CarrierSendCompleteCallback carrierSendCompleteCallback) {
            mCarrierSendCompleteCallback = carrierSendCompleteCallback;
            if (bindToCarrierMessagingService(context, carrierMessagingServicePackage)) {
                Log.v(MmsService.TAG, "bindService() for carrier messaging service succeeded");
            } else {
                Log.e(MmsService.TAG, "bindService() for carrier messaging service failed");
                carrierSendCompleteCallback.onSendMmsComplete(
                        CarrierMessagingService.SEND_STATUS_RETRY_ON_CARRIER_NETWORK,
                        null /* no sendConfPdu */);
            }
        }

        @Override
        protected void onServiceReady(ICarrierMessagingService carrierMessagingService) {
            try {
                Uri locationUri = null;
                if (mLocationUrl != null) {
                    locationUri = Uri.parse(mLocationUrl);
                }
                carrierMessagingService.sendMms(mPduUri, mSubId, locationUri,
                        mCarrierSendCompleteCallback);
            } catch (RemoteException e) {
                Log.e(MmsService.TAG,
                        "Exception sending MMS using the carrier messaging service: " + e);
                mCarrierSendCompleteCallback.onSendMmsComplete(
                        CarrierMessagingService.SEND_STATUS_RETRY_ON_CARRIER_NETWORK,
                        null /* no sendConfPdu */);
            }
        }
    }

    /**
     * A callback which notifies carrier messaging app send result. Once the result is ready, the
     * carrier messaging service connection is disposed.
     */
    private final class CarrierSendCompleteCallback extends
            MmsRequest.CarrierMmsActionCallback {
        private final Context mContext;
        private final CarrierSendManager mCarrierSendManager;

        public CarrierSendCompleteCallback(Context context, CarrierSendManager carrierSendManager) {
            mContext = context;
            mCarrierSendManager = carrierSendManager;
        }

        @Override
        public void onSendMmsComplete(int result, byte[] sendConfPdu) {
            Log.d(MmsService.TAG, "Carrier app result for send: " + result);
            mCarrierSendManager.disposeConnection(mContext);

            if (!maybeFallbackToRegularDelivery(result)) {
                processResult(mContext, toSmsManagerResult(result), sendConfPdu,
                        0/* httpStatusCode */);
            }
        }

        @Override
        public void onDownloadMmsComplete(int result) {
            Log.e(MmsService.TAG, "Unexpected onDownloadMmsComplete call with result: " + result);
        }
    }
}
