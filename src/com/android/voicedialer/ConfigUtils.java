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

package com.android.voicedialer;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.HashMap;

public class ConfigUtils {

    private static final String TAG = "ConfigUtils";

    public static final boolean DEBUG = false;

    public static final String OPEN_ENTRIES = "openentries.txt";

    public static final String ACTION_RECOGNIZER_RESULT =
            "com.android.voicedialer.ACTION_RECOGNIZER_RESULT";
    public static final String SENTENCE_EXTRA = "sentence";
    public static final String SEMANTIC_EXTRA = "semantic";

    public static final String REPORT_FMT = "%6s %6s %6s %6s %6s %6s %6s %s";
    public static final String REPORT_HDR = String.format(REPORT_FMT,
            "1/1", "1/N", "M/N", "0/N", "Fail", "Error", "Total", "");

    public static final String MICROPHONE_EXTRA = "microphone";
    public static final String CONTACTS_EXTRA = "contacts";
    public static final String PHONE_TYPE_EXTRA = "phone_type";

    public static final String SPEAK_NOW_UTTERANCE = "speak_now";
    public static final String TRY_AGAIN_UTTERANCE = "try_again";
    public static final String CHOSEN_ACTION_UTTERANCE = "chose_action";
    public static final String GOODBYE_UTTERANCE = "goodbye";
    public static final String CHOICES_UTTERANCE = "choices";

    public static final int FIRST_UTTERANCE_DELAY = 300;
    public static final int MAX_TTS_DELAY = 6000;
    public static final int EXIT_DELAY = 2000;

    public static final int BLUETOOTH_SAMPLE_RATE = 8000;
    public static final int REGULAR_SAMPLE_RATE = 11025;

    public static final int INITIALIZING = 0;
    public static final int SPEAKING_GREETING = 1;
    public static final int WAITING_FOR_COMMAND = 2;
    public static final int SPEAKING_TRY_AGAIN = 3;
    public static final int SPEAKING_CHOICES = 4;
    public static final int WAITING_FOR_CHOICE = 5;
    public static final int WAITING_FOR_DIALOG_CHOICE = 6;
    public static final int SPEAKING_CHOSEN_ACTION = 7;
    public static final int SPEAKING_GOODBYE = 8;
    public static final int EXITING = 9;

    /**
     * Add a className to a hash table of class name lists.
     * @param openEntries HashMap of lists of class names.
     * @param label a label or word corresponding to the list of classes.
     * @param className class name to add
     */
    public static void addClassName(HashMap<String,String> openEntries,
            String label, String packageName, String className) {
        String component = packageName + "/" + className;
        String labelLowerCase = label.toLowerCase();
        String classList = openEntries.get(labelLowerCase);

        // first item in the list
        if (classList == null) {
            openEntries.put(labelLowerCase, component);
            return;
        }
        // already in list
        int index = classList.indexOf(component);
        int after = index + component.length();
        if (index != -1 && (index == 0 || classList.charAt(index - 1) == ' ') &&
                (after == classList.length() || classList.charAt(after) == ' ')) return;

        // add it to the end
        openEntries.put(labelLowerCase, classList + ' ' + component);
    }

    // map letters in Latin1 Supplement to basic ascii
    // from http://en.wikipedia.org/wiki/Latin-1_Supplement_unicode_block
    // not all letters map well, including Eth and Thorn
    // TODO: this should really be all handled in the pronunciation engine
    public static final char[] mLatin1Letters =
            "AAAAAAACEEEEIIIIDNOOOOO OUUUUYDsaaaaaaaceeeeiiiidnooooo ouuuuydy".
            toCharArray();
    public static final int mLatin1Base = 0x00c0;

    /**
     * Reformat a raw name from the contact list into a form a
     * {@link Recognizer.Grammar} can digest.
     * @param name the raw name.
     * @return the reformatted name.
     */
    public static String scrubName(String name) {
        // replace '&' with ' and '
        name = name.replace("&", " and ");

        // replace '@' with ' at '
        name = name.replace("@", " at ");

        // remove '(...)'
        while (true) {
            int i = name.indexOf('(');
            if (i == -1) break;
            int j = name.indexOf(')', i);
            if (j == -1) break;
            name = name.substring(0, i) + " " + name.substring(j + 1);
        }

        // map letters of Latin1 Supplement to basic ascii
        char[] nm = null;
        for (int i = name.length() - 1; i >= 0; i--) {
            char ch = name.charAt(i);
            if (ch < ' ' || '~' < ch) {
                if (nm == null) nm = name.toCharArray();
                nm[i] = mLatin1Base <= ch && ch < mLatin1Base + mLatin1Letters.length ?
                    mLatin1Letters[ch - mLatin1Base] : ' ';
            }
        }
        if (nm != null) {
            name = new String(nm);
        }

        // if '.' followed by alnum, replace with ' dot '
        while (true) {
            int i = name.indexOf('.');
            if (i == -1 ||
                    i + 1 >= name.length() ||
                    !Character.isLetterOrDigit(name.charAt(i + 1))) break;
            name = name.substring(0, i) + " dot " + name.substring(i + 1);
        }

        // trim
        name = name.trim();

        // ensure at least one alphanumeric character, or the pron engine will fail
        for (int i = name.length() - 1; true; i--) {
            if (i < 0) return "";
            char ch = name.charAt(i);
            if (('a' <= ch && ch <= 'z') || ('A' <= ch && ch <= 'Z') || ('0' <= ch && ch <= '9')) {
                break;
            }
        }

        return name;
    }

    /**
     * Delete all g2g files in the directory indicated by {@link File},
     * which is typically /data/data/com.android.voicedialer/files.
     * There should only be one g2g file at any one time, with a hashcode
     * embedded in it's name, but if stale ones are present, this will delete
     * them all.
     * @param context fetch directory for the stuffed and compiled g2g file.
     */
    public static void deleteAllG2GFiles(Context context) {
        FileFilter ff = new FileFilter() {
            public boolean accept(File f) {
                String name = f.getName();
                return name.endsWith(".g2g");
            }
        };
        File[] files = context.getFilesDir().listFiles(ff);
        if (files != null) {
            for (File file : files) {
                if (DEBUG) Log.d(TAG, "deleteAllG2GFiles " + file);
                file.delete();
            }
        }
    }

    /**
     * Delete G2G and OpenEntries files, to force regeneration of the g2g file
     * from scratch.
     * @param context fetch directory for file.
     */
    public static void deleteCachedGrammarFiles(Context context) {
        deleteAllG2GFiles(context);
        File oe = context.getFileStreamPath(OPEN_ENTRIES);
        if (DEBUG) Log.v(TAG, "deleteCachedGrammarFiles " + oe);
        if (oe.exists()) oe.delete();
    }

    // NANP number formats
    public static final String mNanpFormats =
        "xxx xxx xxxx\n" +
        "xxx xxxx\n" +
        "x11\n";

    // a list of country codes
    public static final String mPlusFormats =

        ////////////////////////////////////////////////////////////
        // zone 1: nanp (north american numbering plan), us, canada, caribbean
        ////////////////////////////////////////////////////////////

        "+1 xxx xxx xxxx\n" +         // nanp

        ////////////////////////////////////////////////////////////
        // zone 2: africa, some atlantic and indian ocean islands
        ////////////////////////////////////////////////////////////

        "+20 x xxx xxxx\n" +          // Egypt
        "+20 1x xxx xxxx\n" +         // Egypt
        "+20 xx xxx xxxx\n" +         // Egypt
        "+20 xxx xxx xxxx\n" +        // Egypt

        "+212 xxxx xxxx\n" +          // Morocco

        "+213 xx xx xx xx\n" +        // Algeria
        "+213 xx xxx xxxx\n" +        // Algeria

        "+216 xx xxx xxx\n" +         // Tunisia

        "+218 xx xxx xxx\n" +         // Libya

        "+22x \n" +
        "+23x \n" +
        "+24x \n" +
        "+25x \n" +
        "+26x \n" +

        "+27 xx xxx xxxx\n" +         // South africa

        "+290 x xxx\n" +              // Saint Helena, Tristan da Cunha

        "+291 x xxx xxx\n" +          // Eritrea

        "+297 xxx xxxx\n" +           // Aruba

        "+298 xxx xxx\n" +            // Faroe Islands

        "+299 xxx xxx\n" +            // Greenland

        ////////////////////////////////////////////////////////////
        // zone 3: europe, southern and small countries
        ////////////////////////////////////////////////////////////

        "+30 xxx xxx xxxx\n" +        // Greece

        "+31 6 xxxx xxxx\n" +         // Netherlands
        "+31 xx xxx xxxx\n" +         // Netherlands
        "+31 xxx xx xxxx\n" +         // Netherlands

        "+32 2 xxx xx xx\n" +         // Belgium
        "+32 3 xxx xx xx\n" +         // Belgium
        "+32 4xx xx xx xx\n" +        // Belgium
        "+32 9 xxx xx xx\n" +         // Belgium
        "+32 xx xx xx xx\n" +         // Belgium

        "+33 xxx xxx xxx\n" +         // France

        "+34 xxx xxx xxx\n" +        // Spain

        "+351 3xx xxx xxx\n" +       // Portugal
        "+351 7xx xxx xxx\n" +       // Portugal
        "+351 8xx xxx xxx\n" +       // Portugal
        "+351 xx xxx xxxx\n" +       // Portugal

        "+352 xx xxxx\n" +           // Luxembourg
        "+352 6x1 xxx xxx\n" +       // Luxembourg
        "+352 \n" +                  // Luxembourg

        "+353 xxx xxxx\n" +          // Ireland
        "+353 xxxx xxxx\n" +         // Ireland
        "+353 xx xxx xxxx\n" +       // Ireland

        "+354 3xx xxx xxx\n" +       // Iceland
        "+354 xxx xxxx\n" +          // Iceland

        "+355 6x xxx xxxx\n" +       // Albania
        "+355 xxx xxxx\n" +          // Albania

        "+356 xx xx xx xx\n" +       // Malta

        "+357 xx xx xx xx\n" +       // Cyprus

        "+358 \n" +                  // Finland

        "+359 \n" +                  // Bulgaria

        "+36 1 xxx xxxx\n" +         // Hungary
        "+36 20 xxx xxxx\n" +        // Hungary
        "+36 21 xxx xxxx\n" +        // Hungary
        "+36 30 xxx xxxx\n" +        // Hungary
        "+36 70 xxx xxxx\n" +        // Hungary
        "+36 71 xxx xxxx\n" +        // Hungary
        "+36 xx xxx xxx\n" +         // Hungary

        "+370 6x xxx xxx\n" +        // Lithuania
        "+370 xxx xx xxx\n" +        // Lithuania

        "+371 xxxx xxxx\n" +         // Latvia

        "+372 5 xxx xxxx\n" +        // Estonia
        "+372 xxx xxxx\n" +          // Estonia

        "+373 6xx xx xxx\n" +        // Moldova
        "+373 7xx xx xxx\n" +        // Moldova
        "+373 xxx xxxxx\n" +         // Moldova

        "+374 xx xxx xxx\n" +        // Armenia

        "+375 xx xxx xxxx\n" +       // Belarus

        "+376 xx xx xx\n" +          // Andorra

        "+377 xxxx xxxx\n" +         // Monaco

        "+378 xxx xxx xxxx\n" +      // San Marino

        "+380 xxx xx xx xx\n" +      // Ukraine

        "+381 xx xxx xxxx\n" +       // Serbia

        "+382 xx xxx xxxx\n" +       // Montenegro

        "+385 xx xxx xxxx\n" +       // Croatia

        "+386 x xxx xxxx\n" +        // Slovenia

        "+387 xx xx xx xx\n" +       // Bosnia and herzegovina

        "+389 2 xxx xx xx\n" +       // Macedonia
        "+389 xx xx xx xx\n" +       // Macedonia

        "+39 xxx xxx xxx\n" +        // Italy
        "+39 3xx xxx xxxx\n" +       // Italy
        "+39 xx xxxx xxxx\n" +       // Italy

        ////////////////////////////////////////////////////////////
        // zone 4: europe, northern countries
        ////////////////////////////////////////////////////////////

        "+40 xxx xxx xxx\n" +        // Romania

        "+41 xx xxx xx xx\n" +       // Switzerland

        "+420 xxx xxx xxx\n" +       // Czech republic

        "+421 xxx xxx xxx\n" +       // Slovakia

        "+421 xxx xxx xxxx\n" +      // Liechtenstein

        "+43 \n" +                   // Austria

        "+44 xxx xxx xxxx\n" +       // UK

        "+45 xx xx xx xx\n" +        // Denmark

        "+46 \n" +                   // Sweden

        "+47 xxxx xxxx\n" +          // Norway

        "+48 xx xxx xxxx\n" +        // Poland

        "+49 1xx xxxx xxx\n" +       // Germany
        "+49 1xx xxxx xxxx\n" +      // Germany
        "+49 \n" +                   // Germany

        ////////////////////////////////////////////////////////////
        // zone 5: latin america
        ////////////////////////////////////////////////////////////

        "+50x \n" +

        "+51 9xx xxx xxx\n" +        // Peru
        "+51 1 xxx xxxx\n" +         // Peru
        "+51 xx xx xxxx\n" +         // Peru

        "+52 1 xxx xxx xxxx\n" +     // Mexico
        "+52 xxx xxx xxxx\n" +       // Mexico

        "+53 xxxx xxxx\n" +          // Cuba

        "+54 9 11 xxxx xxxx\n" +     // Argentina
        "+54 9 xxx xxx xxxx\n" +     // Argentina
        "+54 11 xxxx xxxx\n" +       // Argentina
        "+54 xxx xxx xxxx\n" +       // Argentina

        "+55 xx xxxx xxxx\n" +       // Brazil

        "+56 2 xxxxxx\n" +           // Chile
        "+56 9 xxxx xxxx\n" +        // Chile
        "+56 xx xxxxxx\n" +          // Chile
        "+56 xx xxxxxxx\n" +         // Chile

        "+57 x xxx xxxx\n" +         // Columbia
        "+57 3xx xxx xxxx\n" +       // Columbia

        "+58 xxx xxx xxxx\n" +       // Venezuela

        "+59x \n" +

        ////////////////////////////////////////////////////////////
        // zone 6: southeast asia and oceania
        ////////////////////////////////////////////////////////////

        // TODO is this right?
        "+60 3 xxxx xxxx\n" +        // Malaysia
        "+60 8x xxxxxx\n" +          // Malaysia
        "+60 x xxx xxxx\n" +         // Malaysia
        "+60 14 x xxx xxxx\n" +      // Malaysia
        "+60 1x xxx xxxx\n" +        // Malaysia
        "+60 x xxxx xxxx\n" +        // Malaysia
        "+60 \n" +                   // Malaysia

        "+61 4xx xxx xxx\n" +        // Australia
        "+61 x xxxx xxxx\n" +        // Australia

        // TODO: is this right?
        "+62 8xx xxxx xxxx\n" +      // Indonesia
        "+62 21 xxxxx\n" +           // Indonesia
        "+62 xx xxxxxx\n" +          // Indonesia
        "+62 xx xxx xxxx\n" +        // Indonesia
        "+62 xx xxxx xxxx\n" +       // Indonesia

        "+63 2 xxx xxxx\n" +         // Phillipines
        "+63 xx xxx xxxx\n" +        // Phillipines
        "+63 9xx xxx xxxx\n" +       // Phillipines

        // TODO: is this right?
        "+64 2 xxx xxxx\n" +         // New Zealand
        "+64 2 xxx xxxx x\n" +       // New Zealand
        "+64 2 xxx xxxx xx\n" +      // New Zealand
        "+64 x xxx xxxx\n" +         // New Zealand

        "+65 xxxx xxxx\n" +          // Singapore

        "+66 8 xxxx xxxx\n" +        // Thailand
        "+66 2 xxx xxxx\n" +         // Thailand
        "+66 xx xx xxxx\n" +         // Thailand

        "+67x \n" +
        "+68x \n" +

        "+690 x xxx\n" +             // Tokelau

        "+691 xxx xxxx\n" +          // Micronesia

        "+692 xxx xxxx\n" +          // marshall Islands

        ////////////////////////////////////////////////////////////
        // zone 7: russia and kazakstan
        ////////////////////////////////////////////////////////////

        "+7 6xx xx xxxxx\n" +        // Kazakstan
        "+7 7xx 2 xxxxxx\n" +        // Kazakstan
        "+7 7xx xx xxxxx\n" +        // Kazakstan

        "+7 xxx xxx xx xx\n" +       // Russia

        ////////////////////////////////////////////////////////////
        // zone 8: east asia
        ////////////////////////////////////////////////////////////

        "+81 3 xxxx xxxx\n" +        // Japan
        "+81 6 xxxx xxxx\n" +        // Japan
        "+81 xx xxx xxxx\n" +        // Japan
        "+81 x0 xxxx xxxx\n" +       // Japan

        "+82 2 xxx xxxx\n" +         // South korea
        "+82 2 xxxx xxxx\n" +        // South korea
        "+82 xx xxxx xxxx\n" +       // South korea
        "+82 xx xxx xxxx\n" +        // South korea

        "+84 4 xxxx xxxx\n" +        // Vietnam
        "+84 xx xxxx xxx\n" +        // Vietnam
        "+84 xx xxxx xxxx\n" +       // Vietnam

        "+850 \n" +                  // North Korea

        "+852 xxxx xxxx\n" +         // Hong Kong

        "+853 xxxx xxxx\n" +         // Macau

        "+855 1x xxx xxx\n" +        // Cambodia
        "+855 9x xxx xxx\n" +        // Cambodia
        "+855 xx xx xx xx\n" +       // Cambodia

        "+856 20 x xxx xxx\n" +      // Laos
        "+856 xx xxx xxx\n" +        // Laos

        "+852 xxxx xxxx\n" +         // Hong kong

        "+86 10 xxxx xxxx\n" +       // China
        "+86 2x xxxx xxxx\n" +       // China
        "+86 xxx xxx xxxx\n" +       // China
        "+86 xxx xxxx xxxx\n" +      // China

        "+880 xx xxxx xxxx\n" +      // Bangladesh

        "+886 \n" +                  // Taiwan

        ////////////////////////////////////////////////////////////
        // zone 9: south asia, west asia, central asia, middle east
        ////////////////////////////////////////////////////////////

        "+90 xxx xxx xxxx\n" +       // Turkey

        "+91 9x xx xxxxxx\n" +       // India
        "+91 xx xxxx xxxx\n" +       // India

        "+92 xx xxx xxxx\n" +        // Pakistan
        "+92 3xx xxx xxxx\n" +       // Pakistan

        "+93 70 xxx xxx\n" +         // Afghanistan
        "+93 xx xxx xxxx\n" +        // Afghanistan

        "+94 xx xxx xxxx\n" +        // Sri Lanka

        "+95 1 xxx xxx\n" +          // Burma
        "+95 2 xxx xxx\n" +          // Burma
        "+95 xx xxxxx\n" +           // Burma
        "+95 9 xxx xxxx\n" +         // Burma

        "+960 xxx xxxx\n" +          // Maldives

        "+961 x xxx xxx\n" +         // Lebanon
        "+961 xx xxx xxx\n" +        // Lebanon

        "+962 7 xxxx xxxx\n" +       // Jordan
        "+962 x xxx xxxx\n" +        // Jordan

        "+963 11 xxx xxxx\n" +       // Syria
        "+963 xx xxx xxx\n" +        // Syria

        "+964 \n" +                  // Iraq

        "+965 xxxx xxxx\n" +         // Kuwait

        "+966 5x xxx xxxx\n" +       // Saudi Arabia
        "+966 x xxx xxxx\n" +        // Saudi Arabia

        "+967 7xx xxx xxx\n" +       // Yemen
        "+967 x xxx xxx\n" +         // Yemen

        "+968 xxxx xxxx\n" +         // Oman

        "+970 5x xxx xxxx\n" +       // Palestinian Authority
        "+970 x xxx xxxx\n" +        // Palestinian Authority

        "+971 5x xxx xxxx\n" +       // United Arab Emirates
        "+971 x xxx xxxx\n" +        // United Arab Emirates

        "+972 5x xxx xxxx\n" +       // Israel
        "+972 x xxx xxxx\n" +        // Israel

        "+973 xxxx xxxx\n" +         // Bahrain

        "+974 xxx xxxx\n" +          // Qatar

        "+975 1x xxx xxx\n" +        // Bhutan
        "+975 x xxx xxx\n" +         // Bhutan

        "+976 \n" +                  // Mongolia

        "+977 xxxx xxxx\n" +         // Nepal
        "+977 98 xxxx xxxx\n" +      // Nepal

        "+98 xxx xxx xxxx\n" +       // Iran

        "+992 xxx xxx xxx\n" +       // Tajikistan

        "+993 xxxx xxxx\n" +         // Turkmenistan

        "+994 xx xxx xxxx\n" +       // Azerbaijan
        "+994 xxx xxxxx\n" +         // Azerbaijan

        "+995 xx xxx xxx\n" +        // Georgia

        "+996 xxx xxx xxx\n" +       // Kyrgyzstan

        "+998 xx xxx xxxx\n";        // Uzbekistan


    // TODO: need to handle variable number notation
    public static String formatNumber(String formats, String number) {
        number = number.trim();
        final int nlen = number.length();
        final int formatslen = formats.length();
        StringBuffer sb = new StringBuffer();

        // loop over country codes
        for (int f = 0; f < formatslen; ) {
            sb.setLength(0);
            int n = 0;

            // loop over letters of pattern
            while (true) {
                final char fch = formats.charAt(f);
                if (fch == '\n' && n >= nlen) return sb.toString();
                if (fch == '\n' || n >= nlen) break;
                final char nch = number.charAt(n);
                // pattern matches number
                if (fch == nch || (fch == 'x' && Character.isDigit(nch))) {
                    f++;
                    n++;
                    sb.append(nch);
                }
                // don't match ' ' in pattern, but insert into result
                else if (fch == ' ') {
                    f++;
                    sb.append(' ');
                    // ' ' at end -> match all the rest
                    if (formats.charAt(f) == '\n') {
                        return sb.append(number, n, nlen).toString();
                    }
                }
                // match failed
                else break;
            }

            // step to the next pattern
            f = formats.indexOf('\n', f) + 1;
            if (f == 0) break;
        }

        return null;
    }

    /**
     * Format a phone number string.
     * At some point, PhoneNumberUtils.formatNumber will handle this.
     * @param num phone number string.
     * @return formatted phone number string.
     */
    public static String formatNumber(String num) {
        String fmt = null;

        fmt = formatNumber(mPlusFormats, num);
        if (fmt != null) return fmt;

        fmt = formatNumber(mNanpFormats, num);
        if (fmt != null) return fmt;

        return null;
    }

    // only add if different
    public static void addCallIntent(ArrayList<Intent> intents, Uri uri, String literal,
            String phoneType, int flags) {
        Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED, uri)
                .setFlags(flags)
                .putExtra(SENTENCE_EXTRA, literal)
                .putExtra(PHONE_TYPE_EXTRA, phoneType);
        addIntent(intents, intent);
    }

    public static void addIntent(ArrayList<Intent> intents, Intent intent) {
        for (Intent in : intents) {
            if (in.getAction() != null &&
                    in.getAction().equals(intent.getAction()) &&
                    in.getData() != null &&
                    in.getData().equals(intent.getData())) {
                return;
            }
        }
        intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK);
        intents.add(intent);
    }

    public static String spaceOutDigits(String sentenceDisplay) {
        // if we have a sentence of the form "dial 123 456 7890",
        // we need to insert a space between each digit, otherwise
        // the TTS engine will say "dial one hundred twenty three...."
        // When there already is a space, we also insert a comma,
        // so that it pauses between sections.  For the displayable
        // sentence "dial 123 456 7890" it will speak
        // "dial 1 2 3, 4 5 6, 7 8 9 0"
        char buffer[] = sentenceDisplay.toCharArray();
        StringBuilder builder = new StringBuilder();
        boolean buildingNumber = false;
        int l = sentenceDisplay.length();
        for (int index = 0; index < l; index++) {
            char c = buffer[index];
            if (Character.isDigit(c)) {
                if (buildingNumber) {
                    builder.append(" ");
                }
                buildingNumber = true;
                builder.append(c);
            } else if (c == ' ') {
                if (buildingNumber) {
                    builder.append(",");
                } else {
                    builder.append(" ");
                }
            } else {
                buildingNumber = false;
                builder.append(c);
            }
        }
        return builder.toString();
    }

    public static String countString(int count, int total) {
        return total > 0 ? "" + (100 * count / total) + "%" : "";
    }
}
