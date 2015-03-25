/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.phone.common.mail.store;

import android.util.Log;

import com.android.phone.common.mail.AuthenticationFailedException;
import com.android.phone.common.mail.MessagingException;
import com.android.phone.common.mail.store.imap.ImapConstants;
import com.android.phone.common.mail.store.imap.ImapResponse;
import com.android.phone.common.mail.store.imap.ImapString;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Represents one folder on the IMAP server.
 */
public class ImapFolder {
    private final String TAG = "ImapFolder";
    private ImapStore mStore;
    private String mName;
    private String mMode;
    private boolean mExists;
    private ImapConnection mConnection;
    private int mMessageCount;

    public static final String MODE_READ_ONLY = "mode_read_only";
    public static final String MODE_READ_WRITE = "mode_read_write";

    public ImapFolder(ImapStore store, String name) {
        mStore = store;
        mName = name;
    }

    private void destroyResponses() {
        if (mConnection != null) {
            mConnection.destroyResponses();
        }
    }

    public void open(String mode)
            throws MessagingException {
        try {
            if (isOpen()) {
                if (mMode == mode) {
                    // Make sure the connection is valid.
                    // If it's not we'll close it down and continue on to get a new one.
                    try {
                        mConnection.executeSimpleCommand(ImapConstants.NOOP);
                        return;

                    } catch (IOException ioe) {
                        ioExceptionHandler(mConnection, ioe);
                    } finally {
                        destroyResponses();
                    }
                } else {
                    // Return the connection to the pool, if exists.
                    close(false);
                }
            }
            synchronized (this) {
                mConnection = mStore.getConnection();
            }
            // * FLAGS (\Answered \Flagged \Deleted \Seen \Draft NonJunk
            // $MDNSent)
            // * OK [PERMANENTFLAGS (\Answered \Flagged \Deleted \Seen \Draft
            // NonJunk $MDNSent \*)] Flags permitted.
            // * 23 EXISTS
            // * 0 RECENT
            // * OK [UIDVALIDITY 1125022061] UIDs valid
            // * OK [UIDNEXT 57576] Predicted next UID
            // 2 OK [READ-WRITE] Select completed.
            try {
                doSelect();
            } catch (IOException ioe) {
                throw ioExceptionHandler(mConnection, ioe);
            } finally {
                destroyResponses();
            }
        } catch (AuthenticationFailedException e) {
            // Don't cache this connection, so we're forced to try connecting/login again
            mConnection = null;
            close(false);
            throw e;
        } catch (MessagingException e) {
            mExists = false;
            close(false);
            throw e;
        }
    }

    public boolean isOpen() {
        return mExists && mConnection != null;
    }

    /**
     * Selects the folder for use. Before performing any operations on this folder, it
     * must be selected.
     */
    private void doSelect() throws IOException, MessagingException {
        final List<ImapResponse> responses = mConnection.executeSimpleCommand(
                String.format(Locale.US, ImapConstants.SELECT + " \"%s\"", mName));

        // Assume the folder is opened read-write; unless we are notified otherwise
        mMode = MODE_READ_WRITE;
        int messageCount = -1;
        for (ImapResponse response : responses) {
            if (response.isDataResponse(1, ImapConstants.EXISTS)) {
                messageCount = response.getStringOrEmpty(0).getNumberOrZero();
            } else if (response.isOk()) {
                final ImapString responseCode = response.getResponseCodeOrEmpty();
                if (responseCode.is(ImapConstants.READ_ONLY)) {
                    mMode = MODE_READ_ONLY;
                } else if (responseCode.is(ImapConstants.READ_WRITE)) {
                    mMode = MODE_READ_WRITE;
                }
            } else if (response.isTagged()) { // Not OK
                throw new MessagingException("Can't open mailbox: "
                        + response.getStatusResponseTextOrEmpty());
            }
        }
        if (messageCount == -1) {
            throw new MessagingException("Did not find message count during select");
        }
        mMessageCount = messageCount;
        mExists = true;
    }

    public void close(boolean expunge) {
        // TODO implement expunge
        mMessageCount = -1;
        synchronized (this) {
            mStore.closeConnection();
            mConnection = null;
        }
    }

    private MessagingException ioExceptionHandler(ImapConnection connection, IOException ioe) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "IO Exception detected: ", ioe);
        }
        connection.close();
        if (connection == mConnection) {
            mConnection = null; // To prevent close() from returning the connection to the pool.
            close(false);
        }
        return new MessagingException(MessagingException.IOERROR, "IO Error", ioe);
    }
}
