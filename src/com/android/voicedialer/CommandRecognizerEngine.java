/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.android.voicedialer;


import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.net.Uri;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.speech.srec.Recognizer;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static com.android.voicedialer.ConfigUtils.DEBUG;

/**
 * This is a RecognizerEngine that processes commands to make phone calls and
 * open applications.
 * <ul>
 * <li>setupGrammar
 * <li>Scans contacts and determine if the Grammar g2g file is stale.
 * <li>If so, create and rebuild the Grammar,
 * <li>Else create and load the Grammar from the file.
 * <li>onRecognitionSuccess is called when we get results from the recognizer,
 * it will process the results, which will pass a list of intents to
 * the {@RecognizerClient}.  It will accept the following types of commands:
 * "call" a particular contact
 * "dial a particular number
 * "open" a particular application
 * "redial" the last number called
 * "voicemail" to call voicemail
 * <li>Pass a list of {@link Intent} corresponding to the recognition results
 * to the {@link RecognizerClient}, which notifies the user.
 * </ul>
 * Notes:
 * <ul>
 * <li>Audio many be read from a file.
 * <li>A directory tree of audio files may be stepped through.
 * <li>A contact list may be read from a file.
 * <li>A {@link RecognizerLogger} may generate a set of log files from
 * a recognition session.
 * <li>A static instance of this class is held and reused by the
 * {@link VoiceDialerActivity}, which saves setup time.
 * </ul>
 */
public class CommandRecognizerEngine extends RecognizerEngine {

    private static final int MINIMUM_CONFIDENCE = 100;
    private File mContactsFile;
    private boolean mMinimizeResults;
    private boolean mAllowOpenEntries;
    private HashMap<String,String> mOpenEntries;

    /**
     * Constructor.
     */
    public CommandRecognizerEngine() {
        mContactsFile = null;
        mMinimizeResults = false;
        mAllowOpenEntries = true;
    }

    public void setContactsFile(File contactsFile) {
        if (contactsFile != mContactsFile) {
            mContactsFile = contactsFile;
            // if we change the contacts file, then we need to recreate the grammar.
            if (mSrecGrammar != null) {
                mSrecGrammar.destroy();
                mSrecGrammar = null;
                mOpenEntries = null;
            }
        }
    }

    public void setMinimizeResults(boolean minimizeResults) {
        mMinimizeResults = minimizeResults;
    }

    public void setAllowOpenEntries(boolean allowOpenEntries) {
        if (mAllowOpenEntries != allowOpenEntries) {
            // if we change this setting, then we need to recreate the grammar.
            if (mSrecGrammar != null) {
                mSrecGrammar.destroy();
                mSrecGrammar = null;
                mOpenEntries = null;
            }
        }
        mAllowOpenEntries = allowOpenEntries;
    }

    protected void setupGrammar() throws IOException, InterruptedException {
        // fetch the contact list
        if (DEBUG) Log.d(TAG, "start getVoiceContacts");
        if (DEBUG) Log.d(TAG, "contactsFile is " + (mContactsFile == null ?
            "null" : "not null"));
        List<VoiceContact> contacts = mContactsFile != null ?
                VoiceContact.getVoiceContactsFromFile(mContactsFile) :
                VoiceContact.getVoiceContacts(mActivity);

        // log contacts if requested
        if (mLogger != null) mLogger.logContacts(contacts);
        // generate g2g grammar file name
        File g2g = mActivity.getFileStreamPath("voicedialer." +
                Integer.toHexString(contacts.hashCode()) + ".g2g");

        // rebuild g2g file if current one is out of date
        if (!g2g.exists()) {
            // clean up existing Grammar and old file
            ConfigUtils.deleteAllG2GFiles(mActivity);
            if (mSrecGrammar != null) {
                mSrecGrammar.destroy();
                mSrecGrammar = null;
            }

            // load the empty Grammar
            if (DEBUG) Log.d(TAG, "start new Grammar");
            mSrecGrammar = mSrec.new Grammar(SREC_DIR + "/grammars/VoiceDialer.g2g");
            mSrecGrammar.setupRecognizer();

            // reset slots
            if (DEBUG) Log.d(TAG, "start grammar.resetAllSlots");
            mSrecGrammar.resetAllSlots();

            // add names to the grammar
            addNameEntriesToGrammar(contacts);

            if (mAllowOpenEntries) {
                // add open entries to the grammar
                addOpenEntriesToGrammar();
            }

            // compile the grammar
            if (DEBUG) Log.d(TAG, "start grammar.compile");
            mSrecGrammar.compile();

            // update g2g file
            if (DEBUG) Log.d(TAG, "start grammar.save " + g2g.getPath());
            g2g.getParentFile().mkdirs();
            mSrecGrammar.save(g2g.getPath());
        }

        // g2g file exists, but is not loaded
        else if (mSrecGrammar == null) {
            if (DEBUG) Log.d(TAG, "start new Grammar loading " + g2g);
            mSrecGrammar = mSrec.new Grammar(g2g.getPath());
            mSrecGrammar.setupRecognizer();
        }
        if (mOpenEntries == null && mAllowOpenEntries) {
            // make sure to load the openEntries mapping table.
            loadOpenEntriesTable();
        }

    }

    /**
     * Number of phone ids appended to a grammer in {@link #addNameEntriesToGrammar(List)}.
     */
    private static final int PHONE_ID_COUNT = 7;

    /**
     * Add a list of names to the grammar
     * @param contacts list of VoiceContacts to be added.
     */
    private void addNameEntriesToGrammar(List<VoiceContact> contacts)
            throws InterruptedException {
        if (DEBUG) Log.d(TAG, "addNameEntriesToGrammar " + contacts.size());

        HashSet<String> entries = new HashSet<String>();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (VoiceContact contact : contacts) {
            if (Thread.interrupted()) throw new InterruptedException();
            String name = ConfigUtils.scrubName(contact.mName);
            if (name.length() == 0 || !entries.add(name)) continue;
            sb.setLength(0);
            // The number of ids appended here must be same as PHONE_ID_COUNT.
            sb.append("V='");
            sb.append(contact.mContactId).append(' ');
            sb.append(contact.mPrimaryId).append(' ');
            sb.append(contact.mHomeId).append(' ');
            sb.append(contact.mMobileId).append(' ');
            sb.append(contact.mWorkId).append(' ');
            sb.append(contact.mOtherId).append(' ');
            sb.append(contact.mFallbackId);
            sb.append("'");
            try {
                mSrecGrammar.addWordToSlot("@Names", name, null, 1, sb.toString());
            } catch (Exception e) {
                Log.e(TAG, "Cannot load all contacts to voice recognizer, loaded " +
                        count, e);
                break;
            }

            count++;
        }
    }

    /**
     * add a list of application labels to the 'open x' grammar
     */
    private void loadOpenEntriesTable() throws InterruptedException, IOException {
        if (DEBUG) Log.d(TAG, "addOpenEntriesToGrammar");

        // fill this
        File oe = mActivity.getFileStreamPath(ConfigUtils.OPEN_ENTRIES);

        // build and write list of entries
        if (!oe.exists()) {
            mOpenEntries = new HashMap<String, String>();

            // build a list of 'open' entries
            PackageManager pm = mActivity.getPackageManager();
            List<ResolveInfo> riList = pm.queryIntentActivities(
                            new Intent(Intent.ACTION_MAIN).
                            addCategory("android.intent.category.VOICE_LAUNCH"),
                            PackageManager.GET_ACTIVITIES);
            if (Thread.interrupted()) throw new InterruptedException();
            riList.addAll(pm.queryIntentActivities(
                            new Intent(Intent.ACTION_MAIN).
                            addCategory("android.intent.category.LAUNCHER"),
                            PackageManager.GET_ACTIVITIES));
            String voiceDialerClassName = mActivity.getComponentName().getClassName();

            // scan list, adding complete phrases, as well as individual words
            for (ResolveInfo ri : riList) {
                if (Thread.interrupted()) throw new InterruptedException();

                // skip self
                if (voiceDialerClassName.equals(ri.activityInfo.name)) continue;

                // fetch a scrubbed window label
                String label = ConfigUtils.scrubName(ri.loadLabel(pm).toString());
                if (label.length() == 0) continue;

                // insert it into the result list
                ConfigUtils.addClassName(mOpenEntries, label,
                        ri.activityInfo.packageName, ri.activityInfo.name);

                // split it into individual words, and insert them
                String[] words = label.split(" ");
                if (words.length > 1) {
                    for (String word : words) {
                        word = word.trim();
                        // words must be three characters long, or two if capitalized
                        int len = word.length();
                        if (len <= 1) continue;
                        if (len == 2 && !(Character.isUpperCase(word.charAt(0)) &&
                                        Character.isUpperCase(word.charAt(1)))) continue;
                        if ("and".equalsIgnoreCase(word) ||
                                "the".equalsIgnoreCase(word)) continue;
                        // add the word
                        ConfigUtils.addClassName(mOpenEntries, word,
                                ri.activityInfo.packageName, ri.activityInfo.name);
                    }
                }
            }

            // write list
            if (DEBUG) Log.d(TAG, "addOpenEntriesToGrammar writing " + oe);
            try {
                 FileOutputStream fos = new FileOutputStream(oe);
                 try {
                    ObjectOutputStream oos = new ObjectOutputStream(fos);
                    oos.writeObject(mOpenEntries);
                    oos.close();
                } finally {
                    fos.close();
                }
            } catch (IOException ioe) {
                ConfigUtils.deleteCachedGrammarFiles(mActivity);
                throw ioe;
            }
        }

        // read the list
        else {
            if (DEBUG) Log.d(TAG, "addOpenEntriesToGrammar reading " + oe);
            try {
                FileInputStream fis = new FileInputStream(oe);
                try {
                    ObjectInputStream ois = new ObjectInputStream(fis);
                    mOpenEntries = (HashMap<String, String>)ois.readObject();
                    ois.close();
                } finally {
                    fis.close();
                }
            } catch (Exception e) {
                ConfigUtils.deleteCachedGrammarFiles(mActivity);
                throw new IOException(e.toString());
            }
        }
    }

    private void addOpenEntriesToGrammar() throws InterruptedException, IOException {
        // load up our open entries table
        loadOpenEntriesTable();

        // add list of 'open' entries to the grammar
        for (String label : mOpenEntries.keySet()) {
            if (Thread.interrupted()) throw new InterruptedException();
            String entry = mOpenEntries.get(label);
            // don't add if too many results
            int count = 0;
            for (int i = 0; 0 != (i = entry.indexOf(' ', i) + 1); count++) ;
            if (count > RESULT_LIMIT) continue;
            // add the word to the grammar
            // See Bug: 2457238.
            // We used to store the entire list of components into the grammar.
            // Unfortuantely, the recognizer has a fixed limit on the length of
            // the "semantic" string, which is easy to overflow.  So now,
            // the we store our own mapping table between words and component
            // names, and the entries in the grammar have the same value
            // for literal and semantic.
            mSrecGrammar.addWordToSlot("@Opens", label, null, 1, "V='" + label + "'");
        }
    }

    /**
     * Called when recognition succeeds.  It receives a list
     * of results, builds a corresponding list of Intents, and
     * passes them to the {@link RecognizerClient}, which selects and
     * performs a corresponding action.
     * @param recognizerClient the client that will be sent the results
     */
    protected  void onRecognitionSuccess(RecognizerClient recognizerClient)
            throws InterruptedException {
        if (DEBUG) Log.d(TAG, "onRecognitionSuccess");

        if (mLogger != null) mLogger.logNbestHeader();

        ArrayList<Intent> intents = new ArrayList<Intent>();

        int highestConfidence = 0;
        int examineLimit = RESULT_LIMIT;
        if (mMinimizeResults) {
            examineLimit = 1;
        }
        for (int result = 0; result < mSrec.getResultCount() &&
                intents.size() < examineLimit; result++) {

            // parse the semanticMeaning string and build an Intent
            String conf = mSrec.getResult(result, Recognizer.KEY_CONFIDENCE);
            String literal = mSrec.getResult(result, Recognizer.KEY_LITERAL);
            String semantic = mSrec.getResult(result, Recognizer.KEY_MEANING);
            String msg = "conf=" + conf + " lit=" + literal + " sem=" + semantic;
            if (DEBUG) Log.d(TAG, msg);
            int confInt = Integer.parseInt(conf);
            if (highestConfidence < confInt) highestConfidence = confInt;
            if (confInt < MINIMUM_CONFIDENCE || confInt * 2 < highestConfidence) {
                if (DEBUG) Log.d(TAG, "confidence too low, dropping");
                break;
            }
            if (mLogger != null) mLogger.logLine(msg);
            String[] commands = semantic.trim().split(" ");

            // DIAL 650 867 5309
            // DIAL 867 5309
            // DIAL 911
            if ("DIAL".equalsIgnoreCase(commands[0])) {
                Uri uri = Uri.fromParts("tel", commands[1], null);
                String num =  ConfigUtils.formatNumber(commands[1]);
                if (num != null) {
                    ConfigUtils.addCallIntent(intents, uri,
                            literal.split(" ")[0].trim() + " " + num, "", 0);
                }
            }

            // CALL JACK JONES
            // commands should become ["CALL", id, id, ..] reflecting addNameEntriesToGrammar().
            else if ("CALL".equalsIgnoreCase(commands[0])
                    && commands.length >= PHONE_ID_COUNT + 1) {
                // parse the ids
                long contactId = Long.parseLong(commands[1]); // people table
                long primaryId = Long.parseLong(commands[2]); // phones table
                long homeId    = Long.parseLong(commands[3]); // phones table
                long mobileId  = Long.parseLong(commands[4]); // phones table
                long workId    = Long.parseLong(commands[5]); // phones table
                long otherId   = Long.parseLong(commands[6]); // phones table
                long fallbackId = Long.parseLong(commands[7]); // phones table
                Resources res  = mActivity.getResources();

                int count = 0;

                //
                // generate the best entry corresponding to what was said
                //

                // 'CALL JACK JONES AT HOME|MOBILE|WORK|OTHER'
                if (commands.length == PHONE_ID_COUNT + 2) {
                    // The last command should imply the type of the phone number.
                    final String spokenPhoneIdCommand = commands[PHONE_ID_COUNT + 1];
                    long spokenPhoneId =
                            "H".equalsIgnoreCase(spokenPhoneIdCommand) ? homeId :
                            "M".equalsIgnoreCase(spokenPhoneIdCommand) ? mobileId :
                            "W".equalsIgnoreCase(spokenPhoneIdCommand) ? workId :
                            "O".equalsIgnoreCase(spokenPhoneIdCommand) ? otherId :
                             VoiceContact.ID_UNDEFINED;
                    if (spokenPhoneId != VoiceContact.ID_UNDEFINED) {
                        ConfigUtils.addCallIntent(intents, ContentUris.withAppendedId(
                                Phone.CONTENT_URI, spokenPhoneId),
                                literal, spokenPhoneIdCommand, 0);
                        count++;
                    }
                }

                // 'CALL JACK JONES', with valid default phoneId
                else if (commands.length == PHONE_ID_COUNT + 1) {
                    String phoneType = null;
                    CharSequence phoneIdMsg = null;
                    if (primaryId == VoiceContact.ID_UNDEFINED) {
                        phoneType = null;
                        phoneIdMsg = null;
                    } else if (primaryId == homeId) {
                        phoneType = "H";
                        phoneIdMsg = res.getText(R.string.at_home);
                    } else if (primaryId == mobileId) {
                        phoneType = "M";
                        phoneIdMsg = res.getText(R.string.on_mobile);
                    } else if (primaryId == workId) {
                        phoneType = "W";
                        phoneIdMsg = res.getText(R.string.at_work);
                    } else if (primaryId == otherId) {
                        phoneType = "O";
                        phoneIdMsg = res.getText(R.string.at_other);
                    }
                    if (phoneIdMsg != null) {
                        ConfigUtils.addCallIntent(intents, ContentUris.withAppendedId(
                                Phone.CONTENT_URI, primaryId),
                                literal + phoneIdMsg, phoneType, 0);
                        count++;
                    }
                }

                if (count == 0 || !mMinimizeResults) {
                    //
                    // generate all other entries for this person
                    //

                    // trim last two words, ie 'at home', etc
                    String lit = literal;
                    if (commands.length == PHONE_ID_COUNT + 2) {
                        String[] words = literal.trim().split(" ");
                        StringBuffer sb = new StringBuffer();
                        for (int i = 0; i < words.length - 2; i++) {
                            if (i != 0) {
                                sb.append(' ');
                            }
                            sb.append(words[i]);
                        }
                        lit = sb.toString();
                    }

                    //  add 'CALL JACK JONES at home' using phoneId
                    if (homeId != VoiceContact.ID_UNDEFINED) {
                        ConfigUtils.addCallIntent(intents, ContentUris.withAppendedId(
                                Phone.CONTENT_URI, homeId),
                                lit + res.getText(R.string.at_home), "H",  0);
                        count++;
                    }

                    //  add 'CALL JACK JONES on mobile' using mobileId
                    if (mobileId != VoiceContact.ID_UNDEFINED) {
                        ConfigUtils.addCallIntent(intents, ContentUris.withAppendedId(
                                Phone.CONTENT_URI, mobileId),
                                lit + res.getText(R.string.on_mobile), "M", 0);
                        count++;
                    }

                    //  add 'CALL JACK JONES at work' using workId
                    if (workId != VoiceContact.ID_UNDEFINED) {
                        ConfigUtils.addCallIntent(intents, ContentUris.withAppendedId(
                                Phone.CONTENT_URI, workId),
                                lit + res.getText(R.string.at_work), "W", 0);
                        count++;
                    }

                    //  add 'CALL JACK JONES at other' using otherId
                    if (otherId != VoiceContact.ID_UNDEFINED) {
                        ConfigUtils.addCallIntent(intents, ContentUris.withAppendedId(
                                Phone.CONTENT_URI, otherId),
                                lit + res.getText(R.string.at_other), "O", 0);
                        count++;
                    }

                    if (fallbackId != VoiceContact.ID_UNDEFINED) {
                        ConfigUtils.addCallIntent(intents, ContentUris.withAppendedId(
                                Phone.CONTENT_URI, fallbackId),
                                lit, "", 0);
                        count++;
                    }
                }
            }

            else if ("X".equalsIgnoreCase(commands[0])) {
                Intent intent = new Intent(ConfigUtils.ACTION_RECOGNIZER_RESULT, null);
                intent.putExtra(ConfigUtils.SENTENCE_EXTRA, literal);
                intent.putExtra(ConfigUtils.SEMANTIC_EXTRA, semantic);
                ConfigUtils.addIntent(intents, intent);
            }

            // "CALL VoiceMail"
            else if ("voicemail".equalsIgnoreCase(commands[0]) && commands.length == 1) {
                ConfigUtils.addCallIntent(intents, Uri.fromParts("voicemail", "x", null),
                        literal, "", Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            }

            // "REDIAL"
            else if ("redial".equalsIgnoreCase(commands[0]) && commands.length == 1) {
                String number = VoiceContact.redialNumber(mActivity);
                if (number != null) {
                    ConfigUtils.addCallIntent(intents, Uri.fromParts("tel", number, null),
                            literal, "", Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                }
            }

            // "Intent ..."
            else if ("Intent".equalsIgnoreCase(commands[0])) {
                for (int i = 1; i < commands.length; i++) {
                    try {
                        Intent intent = Intent.getIntent(commands[i]);
                        if (intent.getStringExtra(ConfigUtils.SENTENCE_EXTRA) == null) {
                            intent.putExtra(ConfigUtils.SENTENCE_EXTRA, literal);
                        }
                        ConfigUtils.addIntent(intents, intent);
                    } catch (URISyntaxException e) {
                        if (DEBUG) Log.d(TAG, "onRecognitionSuccess: poorly " +
                                    "formed URI in grammar" + e);
                    }
                }
            }

            // "OPEN ..."
            else if ("OPEN".equalsIgnoreCase(commands[0]) && mAllowOpenEntries) {
                PackageManager pm = mActivity.getPackageManager();
                if (commands.length > 1 & mOpenEntries != null) {
                    // the semantic value is equal to the literal in this case.
                    // We have to do the mapping from this text to the
                    // componentname ourselves.  See Bug: 2457238.
                    // The problem is that the list of all componentnames
                    // can be pretty large and overflow the limit that
                    // the recognizer has.
                    String meaning = mOpenEntries.get(commands[1]);
                    String[] components = meaning.trim().split(" ");
                    for (int i=0; i < components.length; i++) {
                        String component = components[i];
                        Intent intent = new Intent(Intent.ACTION_MAIN);
                        intent.addCategory("android.intent.category.VOICE_LAUNCH");
                        String packageName = component.substring(
                                0, component.lastIndexOf('/'));
                        String className = component.substring(
                                component.lastIndexOf('/')+1, component.length());
                        intent.setClassName(packageName, className);
                        List<ResolveInfo> riList = pm.queryIntentActivities(intent, 0);
                        for (ResolveInfo ri : riList) {
                            String label = ri.loadLabel(pm).toString();
                            intent = new Intent(Intent.ACTION_MAIN);
                            intent.addCategory("android.intent.category.VOICE_LAUNCH");
                            intent.setClassName(packageName, className);
                            intent.putExtra(ConfigUtils.SENTENCE_EXTRA, literal.split(" ")[0] + " " + label);
                            ConfigUtils.addIntent(intents, intent);
                        }
                    }
                }
            }

            // can't parse result
            else {
                if (DEBUG) Log.d(TAG, "onRecognitionSuccess: parse error");
            }
        }

        // log if requested
        if (mLogger != null) mLogger.logIntents(intents);

        // bail out if cancelled
        if (Thread.interrupted()) throw new InterruptedException();

        if (intents.size() == 0) {
            // TODO: strip HOME|MOBILE|WORK and try default here?
            recognizerClient.onRecognitionFailure("No Intents generated");
        } else {
            recognizerClient.onRecognitionSuccess(
                    intents.toArray(new Intent[intents.size()]));
        }
    }
}
