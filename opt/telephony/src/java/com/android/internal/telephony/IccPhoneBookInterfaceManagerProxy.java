/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 */
/* MediaTek Inc. (C) 2010. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER ON
 * AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR NONINFRINGEMENT.
 * NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH RESPECT TO THE
 * SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY, INCORPORATED IN, OR
 * SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES TO LOOK ONLY TO SUCH
 * THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO. RECEIVER EXPRESSLY ACKNOWLEDGES
 * THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES
 * CONTAINED IN MEDIATEK SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK
 * SOFTWARE RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S ENTIRE AND
 * CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE RELEASED HEREUNDER WILL BE,
 * AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE MEDIATEK SOFTWARE AT ISSUE,
 * OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE CHARGE PAID BY RECEIVER TO
 * MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek Software")
 * have been modified by MediaTek Inc. All revisions are subject to any receiver's
 * applicable license agreements with MediaTek Inc.
 */

/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.telephony;

import android.os.ServiceManager;

import com.android.internal.telephony.gsm.UsimPBMemInfo;

import java.util.List;
import com.android.internal.telephony.PhoneConstants;


/**
 * SimPhoneBookInterfaceManager to provide an inter-process communication to
 * access ADN-like SIM records.
 */
public class IccPhoneBookInterfaceManagerProxy extends IIccPhoneBook.Stub {
    private IccPhoneBookInterfaceManager mIccPhoneBookInterfaceManager;

    public IccPhoneBookInterfaceManagerProxy(IccPhoneBookInterfaceManager
            iccPhoneBookInterfaceManager) {
        this(iccPhoneBookInterfaceManager, PhoneConstants.GEMINI_SIM_1);
    }

    // MTK-START [mtk80601][111215][ALPS00093395]
    public IccPhoneBookInterfaceManagerProxy(IccPhoneBookInterfaceManager
            iccPhoneBookInterfaceManager, int simId) {
        mIccPhoneBookInterfaceManager = iccPhoneBookInterfaceManager;

        if (PhoneConstants.GEMINI_SIM_1 == simId) {
            if (ServiceManager.getService("simphonebook") == null) {
                ServiceManager.addService("simphonebook", this);
            }
        } else {
            if (ServiceManager.getService("simphonebook2") == null) {
                ServiceManager.addService("simphonebook2", this);
            }
        }
    }

    // MTK-END [mtk80601][111215][ALPS00093395]
    public void setmIccPhoneBookInterfaceManager(
            IccPhoneBookInterfaceManager iccPhoneBookInterfaceManager) {
        this.mIccPhoneBookInterfaceManager = iccPhoneBookInterfaceManager;
    }

    public boolean updateAdnRecordsInEfBySearch(int efid,
            String oldTag, String oldPhoneNumber,
            String newTag, String newPhoneNumber,
            String pin2) throws android.os.RemoteException {
        return mIccPhoneBookInterfaceManager.updateAdnRecordsInEfBySearch(
                efid, oldTag, oldPhoneNumber, newTag, newPhoneNumber, pin2);
    }

    // MTK-START [mtk80601][111215][ALPS00093395]
    public int updateAdnRecordsInEfBySearchWithError(int efid,
            String oldTag, String oldPhoneNumber,
            String newTag, String newPhoneNumber, String pin2) {

        return mIccPhoneBookInterfaceManager.updateAdnRecordsInEfBySearchWithError(
                efid, oldTag, oldPhoneNumber, newTag, newPhoneNumber, pin2);
    }

    public int updateUsimPBRecordsInEfBySearchWithError(int efid,
            String oldTag, String oldPhoneNumber, String oldAnr, String oldGrpIds,
            String[] oldEmails,
            String newTag, String newPhoneNumber, String newAnr, String newGrpIds,
            String[] newEmails) {

        return mIccPhoneBookInterfaceManager.updateUsimPBRecordsInEfBySearchWithError(
                efid, oldTag, oldPhoneNumber, oldAnr, oldGrpIds, oldEmails, newTag, newPhoneNumber,
                newAnr, newGrpIds, newEmails);
    }

    public int updateUsimPBRecordsBySearchWithError(int efid, AdnRecord oldAdn, AdnRecord newAdn) {

        return mIccPhoneBookInterfaceManager.updateUsimPBRecordsBySearchWithError(efid, oldAdn,
                newAdn);
    }

    // MTK-END [mtk80601][111215][ALPS00093395]
    public boolean updateAdnRecordsInEfByIndex(int efid, String newTag,
            String newPhoneNumber, int index, String pin2) throws android.os.RemoteException {
        return mIccPhoneBookInterfaceManager.updateAdnRecordsInEfByIndex(efid,
                newTag, newPhoneNumber, index, pin2);
    }

    // MTK-START [mtk80601][111215][ALPS00093395]
    public int updateAdnRecordsInEfByIndexWithError(int efid, String newTag,
            String newPhoneNumber, int index, String pin2) {

        return mIccPhoneBookInterfaceManager.updateAdnRecordsInEfByIndexWithError(efid,
                newTag, newPhoneNumber, index, pin2);

    }

    public int updateUsimPBRecordsInEfByIndexWithError(int efid, String newTag,
            String newPhoneNumber, String newAnr, String newGrpIds, String[] newEmails, int index) {

        return mIccPhoneBookInterfaceManager.updateUsimPBRecordsInEfByIndexWithError(efid,
                newTag, newPhoneNumber, newAnr, newGrpIds, newEmails, index);

    }

    public int updateUsimPBRecordsByIndexWithError(int efid, AdnRecord record, int index) {

        return mIccPhoneBookInterfaceManager.updateUsimPBRecordsByIndexWithError(efid, record,
                index);

    }

    // MTK-END [mtk80601][111215][ALPS00093395]

    public int[] getAdnRecordsSize(int efid) throws android.os.RemoteException {
        return mIccPhoneBookInterfaceManager.getAdnRecordsSize(efid);
    }

    public List<AdnRecord> getAdnRecordsInEf(int efid) throws android.os.RemoteException {
        return mIccPhoneBookInterfaceManager.getAdnRecordsInEf(efid);
    }

    // MTK-START [mtk80601][111215][ALPS00093395]
    public boolean isPhbReady() {

        return mIccPhoneBookInterfaceManager.isPhbReady();
    }

    public List<UsimGroup> getUsimGroups() {
        return mIccPhoneBookInterfaceManager.getUsimGroups();
    }

    public String getUSIMGroupById(int nGasId) {
        return mIccPhoneBookInterfaceManager.getUSIMGroupById(nGasId);
    }

    public boolean removeUSIMGroupById(int nGasId) {
        return mIccPhoneBookInterfaceManager.removeUSIMGroupById(nGasId);
    }

    public int insertUSIMGroup(String grpName) {
        return mIccPhoneBookInterfaceManager.insertUSIMGroup(grpName);
    }

    public int updateUSIMGroup(int nGasId, String grpName) {
        return mIccPhoneBookInterfaceManager.updateUSIMGroup(nGasId, grpName);
    }

    public boolean addContactToGroup(int adnIndex, int grpIndex) {
        return mIccPhoneBookInterfaceManager.addContactToGroup(adnIndex, grpIndex);
    }

    public boolean removeContactFromGroup(int adnIndex, int grpIndex) {
        return mIccPhoneBookInterfaceManager.removeContactFromGroup(adnIndex, grpIndex);
    }

    public int hasExistGroup(String grpName) {
        return mIccPhoneBookInterfaceManager.hasExistGroup(grpName);
    }

    public int getUSIMGrpMaxNameLen() {
        return mIccPhoneBookInterfaceManager.getUSIMGrpMaxNameLen();
    }

    public int getUSIMGrpMaxCount() {
        return mIccPhoneBookInterfaceManager.getUSIMGrpMaxCount();
    }

    public List<AlphaTag> getUSIMAASList() {
        return mIccPhoneBookInterfaceManager.getUSIMAASList();
    }

    public String getUSIMAASById(int index) {
        return mIccPhoneBookInterfaceManager.getUSIMAASById(index);
    }

    public boolean removeUSIMAASById(int index, int pbrIndex) {
        return mIccPhoneBookInterfaceManager.removeUSIMAASById(index, pbrIndex);
    }

    public int insertUSIMAAS(String aasName) {
        return mIccPhoneBookInterfaceManager.insertUSIMAAS(aasName);
    }

    public boolean updateUSIMAAS(int index, int pbrIndex, String aasName) {
        return mIccPhoneBookInterfaceManager.updateUSIMAAS(index, pbrIndex, aasName);
    }

    /**
     * @param adnIndex: ADN index
     * @param aasIndex: change AAS to the value refered by aasIndex, -1 means
     *            remove
     * @return
     */
    public boolean updateADNAAS(int adnIndex, int aasIndex) {
        return mIccPhoneBookInterfaceManager.updateADNAAS(adnIndex, aasIndex);
    }

    /**
     * @return the maximum number of entries associated with an EF_ADN
     */
    public int getAnrCount() {
        return mIccPhoneBookInterfaceManager.getAnrCount();
    }

    public int getUSIMAASMaxCount() {
        return mIccPhoneBookInterfaceManager.getUSIMAASMaxCount();
    }

    public int getUSIMAASMaxNameLen() {
        return mIccPhoneBookInterfaceManager.getUSIMAASMaxNameLen();
    }

    public boolean hasSne() {
        return mIccPhoneBookInterfaceManager.hasSne();
    }

    public int getSneRecordLen() {
        return mIccPhoneBookInterfaceManager.getSneRecordLen();
    }

    /**
     * M for LGE APIs request--get phonebook mem storage ext like record
     * (length, total, used, type)
     * 
     * @return UsimPBMemInfo list
     */
    public UsimPBMemInfo[] getPhonebookMemStorageExt() {
        return mIccPhoneBookInterfaceManager.getPhonebookMemStorageExt();
    }
    // MTK-END [mtk80601][111215][ALPS00093395]

}
