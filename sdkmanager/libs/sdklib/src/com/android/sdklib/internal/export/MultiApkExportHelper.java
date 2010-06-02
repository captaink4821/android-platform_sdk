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

package com.android.sdklib.internal.export;

import com.android.sdklib.SdkConstants;
import com.android.sdklib.internal.project.ApkSettings;
import com.android.sdklib.internal.project.ProjectProperties;
import com.android.sdklib.internal.project.ProjectProperties.PropertyType;
import com.android.sdklib.io.FileWrapper;
import com.android.sdklib.io.IAbstractFile;
import com.android.sdklib.io.IAbstractFolder;
import com.android.sdklib.io.IAbstractResource;
import com.android.sdklib.io.StreamException;
import com.android.sdklib.io.IAbstractFolder.FilenameFilter;
import com.android.sdklib.xml.AndroidManifestParser;
import com.android.sdklib.xml.ManifestData;
import com.android.sdklib.xml.ManifestData.SupportsScreens;

import org.xml.sax.SAXException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;

/**
 * Helper to export multiple APKs from 1 or or more projects.
 */
public class MultiApkExportHelper {

    private final String mAppPackage;
    private final int mVersionCode;
    private final Target mTarget;

    final static int MAX_MINOR = 100;
    final static int MAX_BUILDINFO = 100;
    final static int OFFSET_BUILD_INFO = MAX_MINOR;
    final static int OFFSET_VERSION_CODE = OFFSET_BUILD_INFO * MAX_BUILDINFO;

    public static final class ExportException extends Exception {
        private static final long serialVersionUID = 1L;

        public ExportException(String message) {
            super(message);
        }

        public ExportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static enum Target {
        RELEASE("release"), CLEAN("clean");

        private final String mName;

        Target(String name) {
            mName = name;
        }

        public String getTarget() {
            return mName;
        }

        public static Target getTarget(String value) {
            for (Target t : values()) {
                if (t.mName.equals(value)) {
                    return t;
                }

            }

            return null;
        }
    }

    /**
     * Simple class to hold a {@link ManifestData} and the {@link IAbstractFile} representing
     * the parsed manifest file.
     */
    private static class Manifest {
        final IAbstractFile file;
        final ManifestData data;

        Manifest(IAbstractFile file, ManifestData data) {
            this.file = file;
            this.data = data;
        }
    }

    public MultiApkExportHelper(String appPackage, int versionCode, Target target) {
        mAppPackage = appPackage;
        mVersionCode = versionCode;
        mTarget = target;
    }

    public ApkData[] getProjects(String projects, IAbstractFile buildLog) throws ExportException {
        // get the list of apk to export and their configuration.
        ApkData[] apks = getProjects(projects);

        // look to see if there's an export log from a previous export
        if (mTarget == Target.RELEASE && buildLog != null && buildLog.exists()) {
            // load the log and compare to current export list.
            // Any difference will force a new versionCode.
            ApkData[] previousApks = getProjects(buildLog);

            if (previousApks.length != apks.length) {
                throw new ExportException(String.format(
                        "Project export is setup differently from previous export at versionCode %d.\n" +
                        "Any change in the multi-apk configuration requires an increment of the versionCode in export.properties.",
                        mVersionCode));
            }

            for (int i = 0 ; i < previousApks.length ; i++) {
                // update the minor value from what is in the log file.
                apks[i].setMinor(previousApks[i].getMinor());
                if (apks[i].hasSameApkProperties(previousApks[i]) == false) {
                    throw new ExportException(String.format(
                            "Project export is setup differently from previous export at versionCode %d.\n" +
                            "Any change in the multi-apk configuration requires an increment of the versionCode.",
                            mVersionCode));
                }
            }
        }

        return apks;
    }

    /**
     * Writes the build log for a given list of {@link ApkData}.
     * @param buildLog the build log file into which to write the log.
     * @param apks the list of apks that were exported.
     * @throws ExportException
     */
    public void makeBuildLog(IAbstractFile buildLog, ApkData[] apks) throws ExportException {
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(buildLog.getOutputStream());

            writer.append(
                    "# Multi-APK BUILD LOG.\n" +
                    "# This file serves two purpose:\n" +
                    "# - A log of what was built, showing what went in each APK and their properties.\n" +
                    "#   You can refer to this if you get a bug report for a specific versionCode.\n" +
                    "# - A way to update builds through minor revisions for specific APKs.\n" +
                    "# Only edit manually to change the minor properties for build you wish to respin.\n" +
                    "# Note that all APKs will be regenerated all the time.\n");

            writeValue(writer, "package", mAppPackage);
            writeValue(writer, "versionCode", mVersionCode);

            writer.append(
                    "# The format of the following lines is:\n" +
                    "# <filename>:<property1>;<property2>;<property3>;...\n" +
                    "# Properties are written as <name>=<value>\n");

            for (ApkData apk : apks) {
                writer.append(apk.getLogLine(null));
                writer.append('\n');

                // display the soft variants for this apkData as comments to the log file.
                // since they all share the same Build Info and cannot be modified by the dev
                // and we won't read them back from the log.
                Map<String, String> softVariants = apk.getSoftVariantMap();
                if (softVariants.size() > 0) {
                    writer.append(" # Soft Variants -- DO NOT UNCOMMENT:\n");
                }

                for (String softVariant : softVariants.keySet()) {
                    writer.append(" # ");
                    writer.append(apk.getLogLine(softVariant));
                    writer.append('\n');
                }
            }

            writer.flush();
        } catch (Exception e) {
            throw new ExportException("Failed to write build log", e);
        } finally {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException e) {
                throw new ExportException("Failed to write build log", e);
            }
        }
    }

    private void writeValue(OutputStreamWriter writer, String name, String value)
            throws IOException {
        writer.append(name).append('=').append(value).append('\n');
    }

    private void writeValue(OutputStreamWriter writer, String name, int value) throws IOException {
        writeValue(writer, name, Integer.toString(value));
    }

    /**
     * gets the projects to export from the property, checks they exist, validates them,
     * loads their export info and return it.
     * If a project does not exist or is not valid, this will throw a {@link BuildException}.
     * @param projects the string containing all the relative paths to the projects. This is
     * usually read from export.properties.
     * @throws ExportException
     */
    private ApkData[] getProjects(String projects) throws ExportException {
        String[] paths = projects.split("\\:");

        ArrayList<ApkData> datalist = new ArrayList<ApkData>();
        ArrayList<Manifest> manifests = new ArrayList<Manifest>();

        for (String path : paths) {
            File projectFolder = new File(path);

            // resolve the path (to remove potential ..)
            try {
                projectFolder = projectFolder.getCanonicalFile();

                // project folder must exist and be a directory
                if (projectFolder.isDirectory() == false) {
                    throw new ExportException(String.format(
                            "Project folder '%1$s' is not a valid directory.",
                            projectFolder.getAbsolutePath()));
                }

                // Check AndroidManifest.xml is present
                FileWrapper androidManifest = new FileWrapper(projectFolder,
                        SdkConstants.FN_ANDROID_MANIFEST_XML);

                if (androidManifest.isFile() == false) {
                    throw new ExportException(String.format(
                            "%1$s is not a valid project (%2$s not found).",
                            projectFolder.getAbsolutePath(),
                            SdkConstants.FN_ANDROID_MANIFEST_XML));
                }

                ArrayList<ApkData> datalist2 = checkProject(androidManifest, manifests);

                // if the method returns without throwing, this is a good project to
                // export.
                for (ApkData data : datalist2) {
                    data.setRelativePath(path);
                    data.setProject(projectFolder);
                }

                datalist.addAll(datalist2);

            } catch (IOException e) {
                throw new ExportException(String.format("Failed to resolve path %1$s", path), e);
            }
        }

        // sort the projects and assign buildInfo
        Collections.sort(datalist);
        int buildInfo = 0;
        for (ApkData data : datalist) {
            data.setBuildInfo(buildInfo++);
        }

        return datalist.toArray(new ApkData[datalist.size()]);
    }

    /**
     * Checks a project inclusion in the list of exported APK.
     * <p/>This performs a check on the manifest, as well as gathers more information about
     * mutli-apk from the project's default.properties file.
     * If the manifest is correct, a list of apk to export is created and returned.
     *
     * @param androidManifest the manifest to check
     * @param manifests list of manifests that were already parsed. Must be filled with the current
     * manifest being checked.
     * @return A new non-null {@link ArrayList} of {@link ApkData}.
     * @throws ExportException in case of error.
     */
    private ArrayList<ApkData> checkProject(IAbstractFile androidManifest,
            ArrayList<Manifest> manifests) throws ExportException {
        try {
            ManifestData manifestData = AndroidManifestParser.parse(androidManifest);

            String manifestPackage = manifestData.getPackage();
            if (mAppPackage.equals(manifestPackage) == false) {
                throw new ExportException(String.format(
                        "%1$s package value is not valid. Found '%2$s', expected '%3$s'.",
                        androidManifest.getOsLocation(), manifestPackage, mAppPackage));
            }

            if (manifestData.getVersionCode() != null) {
                throw new ExportException(String.format(
                        "%1$s is not valid: versionCode must not be set for multi-apk export.",
                        androidManifest.getOsLocation()));
            }

            int minSdkVersion = manifestData.getMinSdkVersion();
            if (minSdkVersion == ManifestData.MIN_SDK_CODENAME) {
                throw new ExportException(
                        "Codename in minSdkVersion is not supported by multi-apk export.");
            }

            // compare to other existing manifest.
            for (Manifest previousManifest : manifests) {
                // Multiple apk export support difference in:
                // - min SDK Version
                // - Screen version
                // - GL version
                // - ABI (not managed at the Manifest level).
                // if those values are the same between 2 manifest, then it's an error.

                // first the minSdkVersion.
                if (minSdkVersion == previousManifest.data.getMinSdkVersion()) {
                    // if it's the same compare the rest.
                    SupportsScreens currentSS = manifestData.getSupportsScreensValues();
                    SupportsScreens previousSS = previousManifest.data.getSupportsScreensValues();
                    boolean sameSupportsScreens = currentSS.hasSameScreenSupportAs(previousSS);

                    // if it's the same, then it's an error. Can't export 2 projects that have the
                    // same approved (for multi-apk export) hard-properties.
                    if (manifestData.getGlEsVersion() == previousManifest.data.getGlEsVersion() &&
                            sameSupportsScreens) {

                        throw new ExportException(String.format(
                                "Android manifests must differ in at least one of the following values:\n" +
                                "- minSdkVersion\n" +
                                "- SupportsScreen (screen sizes only)\n" +
                                "- GL ES version.\n" +
                                "%1$s and %2$s are considered identical for multi-apk export.",
                                androidManifest.getOsLocation(),
                                previousManifest.file.getOsLocation()));
                    }

                    // At this point, either supports-screens or GL are different.
                    // Because supports-screens is the highest priority properties to be
                    // (potentially) different, we must do some extra checks on it.
                    // It must either be the same in both projects (difference is only on GL value),
                    // or follow theses rules:
                    // - Property in each projects must be strictly different, ie both projects
                    //   cannot support the same screen size(s).
                    // - Property in each projects cannot overlap, ie a projects cannot support
                    //   both a lower and a higher screen size than the other project.
                    //   (ie APK1 supports small/large and APK2 supports normal).
                    if (sameSupportsScreens == false) {
                        if (currentSS.hasStrictlyDifferentScreenSupportAs(previousSS) == false) {
                            throw new ExportException(String.format(
                                    "APK differentiation by Supports-Screens cannot support different APKs supporting the same screen size.\n" +
                                    "%1$s supports %2$s\n" +
                                    "%3$s supports %4$s\n",
                                    androidManifest.getOsLocation(), currentSS.toString(),
                                    previousManifest.file.getOsLocation(), previousSS.toString()));
                        }

                        if (currentSS.overlapWith(previousSS)) {
                            throw new ExportException(String.format(
                                    "Unable to compute APK priority due to incompatible difference in Supports-Screens values.\n" +
                                    "%1$s supports %2$s\n" +
                                    "%3$s supports %4$s\n",
                                    androidManifest.getOsLocation(), currentSS.toString(),
                                    previousManifest.file.getOsLocation(), previousSS.toString()));
                        }
                    }
                }
            }

            // add the current manifest to the list
            manifests.add(new Manifest(androidManifest, manifestData));

            ArrayList<ApkData> dataList = new ArrayList<ApkData>();
            ApkData data = new ApkData(minSdkVersion, manifestData.getSupportsScreensValues(),
                    manifestData.getGlEsVersion());
            dataList.add(data);

            // only look for more exports if the target is not clean.
            if (mTarget != Target.CLEAN) {
                // load the project properties
                IAbstractFolder projectFolder = androidManifest.getParentFolder();
                ProjectProperties projectProp = ProjectProperties.load(projectFolder,
                        PropertyType.DEFAULT);
                if (projectProp == null) {
                    throw new ExportException(String.format(
                            "%1$s is missing.", PropertyType.DEFAULT.getFilename()));
                }

                ApkSettings apkSettings = new ApkSettings(projectProp);

                // get the density/locale values
                boolean splitByDensity = apkSettings.isSplitByDensity();
                Map<String, String> localeFilters = apkSettings.getLocaleFilters();

                if (apkSettings.isSplitByAbi()) {
                    // need to find the available ABIs.
                    List<String> abis = findAbis(projectFolder);
                    ApkData current = data;
                    for (String abi : abis) {
                        if (current == null) {
                            current = new ApkData(data);
                            dataList.add(current);
                        }

                        current.setAbi(abi);
                        current.setSplitDensity(splitByDensity);
                        current.setLocaleFilters(localeFilters);

                        current = null;
                    }
                }
            }

            return dataList;
        } catch (SAXException e) {
            throw new ExportException(
                    String.format("Failed to validate %1$s", androidManifest.getOsLocation()), e);
        } catch (IOException e) {
            throw new ExportException(
                    String.format("Failed to validate %1$s", androidManifest.getOsLocation()), e);
        } catch (StreamException e) {
            throw new ExportException(
                    String.format("Failed to validate %1$s", androidManifest.getOsLocation()), e);
        } catch (ParserConfigurationException e) {
            throw new ExportException(
                    String.format("Failed to validate %1$s", androidManifest.getOsLocation()), e);
        }
    }

    /**
     * Loads and returns a list of {@link ApkData} from a build log.
     * @param log
     * @return A new non-null, possibly empty, array of {@link ApkData}.
     * @throws ExportException
     * @throws BuildException in case of error.
     */
    private ApkData[] getProjects(IAbstractFile buildLog) throws ExportException {
        ArrayList<ApkData> datalist = new ArrayList<ApkData>();

        InputStreamReader reader = null;
        BufferedReader bufferedReader = null;
        try {
            reader = new InputStreamReader(buildLog.getContents());
            bufferedReader = new BufferedReader(reader);
            String line;
            int lineIndex = 0;
            while ((line = bufferedReader.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) {
                    continue;
                }

                switch (lineIndex) {
                    case 0:
                        // read package value
                        lineIndex++;
                        break;
                    case 1:
                        // read versionCode value
                        lineIndex++;
                        break;
                    default:
                        // read apk description
                        ApkData data = new ApkData();
                        datalist.add(data);
                        data.initFromLogLine(line);
                        if (data.getMinor() >= MAX_MINOR) {
                            throw new ExportException(
                                    "Valid minor version code values are 0-" + (MAX_MINOR-1));
                        }
                        break;
                }
            }
        } catch (IOException e) {
            throw new ExportException("Failed to read existing build log", e);
        } catch (StreamException e) {
            throw new ExportException("Failed to read existing build log", e);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                throw new ExportException("Failed to read existing build log", e);
            }
        }

        return datalist.toArray(new ApkData[datalist.size()]);
    }

    /**
     * Finds ABIs in a project folder. This is based on the presence of libs/<abi>/ folder.
     *
     * @param projectPath The OS path of the project.
     * @return A new non-null, possibly empty, list of ABI strings.
     */
    private List<String> findAbis(IAbstractFolder projectFolder) {
        ArrayList<String> abiList = new ArrayList<String>();
        IAbstractFolder libs = projectFolder.getFolder(SdkConstants.FD_NATIVE_LIBS);
        if (libs.exists()) {
            IAbstractResource[] abis = libs.listMembers();
            for (IAbstractResource abi : abis) {
                if (abi instanceof IAbstractFolder && abi.exists()) {
                    // only add the abi folder if there are .so files in it.
                    String[] content = ((IAbstractFolder)abi).list(new FilenameFilter() {
                        public boolean accept(IAbstractFolder dir, String name) {
                            return name.toLowerCase().endsWith(".so");
                        }
                    });

                    if (content.length > 0) {
                        abiList.add(abi.getName());
                    }
                }
            }
        }

        return abiList;
    }


}