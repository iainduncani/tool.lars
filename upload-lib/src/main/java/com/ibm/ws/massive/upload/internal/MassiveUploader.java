/*******************************************************************************
 * Copyright (c) 2015 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/

package com.ibm.ws.massive.upload.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.Stack;
import java.util.StringTokenizer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import com.ibm.ws.massive.LoginInfoEntry;
import com.ibm.ws.massive.RepositoryException;
import com.ibm.ws.massive.esa.internal.EsaManifest;
import com.ibm.ws.massive.internal.AbstractMassive;
import com.ibm.ws.massive.resources.ImageDetails;
import com.ibm.ws.massive.resources.MassiveResource;
import com.ibm.ws.massive.resources.MassiveResource.AttachmentLinkType;
import com.ibm.ws.massive.resources.MassiveResource.AttachmentResource;
import com.ibm.ws.massive.resources.MassiveResource.AttachmentType;
import com.ibm.ws.massive.resources.MassiveResource.DownloadPolicy;
import com.ibm.ws.massive.resources.MassiveResource.LicenseType;
import com.ibm.ws.massive.upload.RepositoryArchiveEntryNotFoundException;
import com.ibm.ws.massive.upload.RepositoryArchiveException;
import com.ibm.ws.massive.upload.RepositoryArchiveIOException;
import com.ibm.ws.massive.upload.RepositoryArchiveInvalidEntryException;
import com.ibm.ws.massive.utils.RepositoryUtils;

/**
 * Base class for upload utilities.
 */
public abstract class MassiveUploader extends AbstractMassive {

    /**
     * Key for a property stating what type of link this is (only relevant if the downloadUrl is
     * also set), can be one DIRECT, WEB_PAGE or EFD. Defaults to DIRECT.
     */
    public final static String LINK_TYPE_PROPERTY_KEY = "linkType";
    /*
     * This header is used with in products (JARs) to record the location of License Agreement files
     * within the archive.
     */
    public final static String LA_HEADER_PRODUCT = "License-Agreement";

    /*
     * This header is used in products (JARs) to record the location of License Information files
     * within the archive.
     */
    public final static String LI_HEADER_PRODUCT = "License-Information";

    /* Header used in features (ESAs) to record License Agreement location */
    public final static String LA_HEADER_FEATURE = "IBM-License-Agreement";

    /* Header used in features (ESAs) to record License Information location */
    public final static String LI_HEADER_FEATURE = "IBM-License-Information";

    /* Properties in props file */
    public final static String PROP_REQUIRE_FEATURE = "requires.feature";
    public final static String PROP_APPLIES_TO_MIN_VERSION = "applies.to.minimum.version";
    public final static String PROP_APPLIES_TO_EDITIONS = "applies.to.editions";
    public final static String PROP_PROVIDER_NAME = "provider";
    public final static String PROP_NAME = "name";
    public final static String PROP_DESCRIPTION = "longDescription";
    public final static String PROP_SHORT_DESCRIPTION = "shortDescription";
    public final static String PROP_URL = "url";
    public final static String PROP_ICONS = "icons";
    public final static String PROP_DOWNLOAD_URL = "downloadURL";
    public final static String PROP_STAGED_URL = "stagedURL";

    public MassiveUploader(LoginInfoEntry loginInfo) {
        super(loginInfo);
    }

    /**
     * Enum declaring the productEdition component of appliesTo
     */
    public enum Edition {
        CORE("CORE"),
        BASE("BASE"),
        DEVELOPERS("DEVELOPERS"),
        EXPRESS("EXPRESS"),
        ND("ND"),
        zOS("zOS");

        private String _productEdition;

        private Edition(String productEdition) {
            _productEdition = productEdition;
        }

        public String getProductEdition() {
            return _productEdition;
        }
    }

    /**
     * Reads from the input stream and copies to the output stream
     *
     * @param is
     * @param os
     * @throws IOException
     */
    protected void copyStreams(InputStream is, OutputStream os)
            throws IOException {
        byte[] buffer = new byte[1024];
        try {
            int read;
            while ((read = is.read(buffer)) != -1) {
                os.write(buffer, 0, read);
                buffer = new byte[1024];
            }
        } finally {
            try {
                if (null != os)
                    os.close();
            } finally {
                if (null != is)
                    is.close();
            }
        }
    }

    public static class ExtractedFileInformation {
        private final File extractedFile;
        private final File sourceArchive;
        private final String selectedPathFromArchive;

        public ExtractedFileInformation(File extractedFile, File sourceArchive,
                                        String selectedPathFromArchive) {
            super();
            this.extractedFile = extractedFile;
            this.sourceArchive = sourceArchive;
            this.selectedPathFromArchive = selectedPathFromArchive;
        }

        public File getExtractedFile() {
            return extractedFile;
        }

        public File getSourceArchive() {
            return sourceArchive;
        }

        public String getSelectedPathFromArchive() {
            return selectedPathFromArchive;
        }

    }

    /**
     * Extract a file from a jar file to a temporary location on disk that is deleted when the jvm
     * exits.
     *
     * @param fileName The name of the archive file to extract the file from
     * @param regex A regular expression, that is used to match the file to be extracted from the
     *            archive. If more than one file matches the regular expression only the first file
     *            is extracted
     * @return A file object representing the temporary file where the file in the jar was extracted
     *         to.
     * @throws MassiveArchiveException if the archive could not be read or the file could not be
     *             extracted to disk
     * @throws RepositoryArchiveException
     * @throws RepositoryArchiveIOException
     * @throws RepositoryArchiveEntryNotFoundException If no file matching the supplied regular
     *             expression could be found
     */
    protected ExtractedFileInformation extractFileFromArchive(String fileName,
                                                              String regex) throws RepositoryArchiveException,
            RepositoryArchiveEntryNotFoundException,
            RepositoryArchiveIOException {

        JarFile jarFile = null;
        ExtractedFileInformation result = null;
        File outputFile = null;
        File sourceArchive = new File(fileName);

        try {
            try {
                jarFile = new JarFile(sourceArchive);
            } catch (FileNotFoundException fne) {
                throw new RepositoryArchiveException(
                        "Unable to locate archive " + fileName, sourceArchive,
                        fne);
            } catch (IOException ioe) {
                throw new RepositoryArchiveIOException(
                        "Error opening archive ", sourceArchive, ioe);
            }

            Enumeration<JarEntry> enumEntries = jarFile.entries();

            // Iterate through the files in the jar file searching for one we
            // are interested in
            while (enumEntries.hasMoreElements()) {
                JarEntry entry = enumEntries.nextElement();
                String name = entry.getName();

                if (Pattern.matches(regex, name)) {
                    // Don't want to use the entire path to the file so create a
                    // file
                    // and then recreate it using just the "name" part of the
                    // filename
                    outputFile = new File(name);
                    outputFile = new File(outputFile.getName());
                    outputFile.deleteOnExit();

                    try {
                        copyStreams(jarFile.getInputStream(entry),
                                    new FileOutputStream(outputFile));
                    } catch (IOException ioe) {
                        throw new RepositoryArchiveIOException(
                                "Failed to extract file " + name + " inside "
                                        + fileName + " to "
                                        + outputFile.getAbsolutePath(),
                                sourceArchive, ioe);
                    }

                    result = new ExtractedFileInformation(outputFile,
                            sourceArchive, name);

                    break;
                }
            }
        } finally {
            try {
                if (null != jarFile) {
                    jarFile.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
                throw new RepositoryArchiveIOException(
                        "Error closing archive ", sourceArchive, e);
            }
        }

        // Make sure we have found a file
        if (null == outputFile) {
            throw new RepositoryArchiveEntryNotFoundException(
                    "Failed to find file matching regular expression <" + regex
                            + "> inside archive " + fileName, sourceArchive,
                    regex);
        }

        return result;
    }

    public File unpackToTempDir(File zipFile) throws IOException {
        File tempDir = File.createTempFile("packedEsa", null);
        if (!tempDir.delete()) {
            throw new IOException("Couldn't delete temp file "
                                  + tempDir.getAbsolutePath());
        }
        if (!tempDir.mkdir()) {
            throw new IOException("Couldn't create temp dir "
                                  + tempDir.getAbsolutePath());
        }
        tempDir.deleteOnExit();
        FileInputStream fis = new FileInputStream(zipFile);
        ZipInputStream zis = new ZipInputStream(fis);
        try {
            ZipEntry ze = zis.getNextEntry();
            byte[] buf = new byte[2048];
            while (ze != null) {
                if (ze.isDirectory()) {
                    // Do nothing with pure directory entries
                } else {
                    String fileName = ze.getName();
                    File unzippedFile = new File(tempDir.getCanonicalPath()
                                                 + File.separator + fileName);
                    File parent = new File(unzippedFile.getParent());
                    if (!parent.mkdirs() && !parent.exists()) {
                        throw new IOException("Couldn't create dir "
                                              + parent.getAbsolutePath());
                    }
                    FileOutputStream fos = new FileOutputStream(unzippedFile);
                    try {
                        int numBytes;
                        while ((numBytes = zis.read(buf)) > 0) {
                            fos.write(buf, 0, numBytes);
                        }
                    } finally {
                        fos.close();
                    }
                }
                ze = zis.getNextEntry();
            }
        } finally {
            zis.closeEntry();
            zis.close();
        }

        // Now mark everything below tempDir for deletion.
        for (File f : listAllFilesAndDirectories(tempDir)) {
            f.deleteOnExit();
        }
        return tempDir;
    }

    public Collection<File> listAllFilesAndDirectories(File rootDir) {
        return doListAllFiles(rootDir, true);
    }

    public Collection<File> listAllFiles(File rootDir) {
        return doListAllFiles(rootDir, false);
    }

    // File.deleteOnExit():
    // "Files (or directories) are deleted in the reverse order that they are registered. "
    private Collection<File> doListAllFiles(File rootDir,
                                            boolean includeDirectories) {
        Collection<File> result = new Stack<File>();
        File[] files = rootDir.listFiles();
        for (File f : files) {
            if (f.isDirectory()) {
                result.addAll(doListAllFiles(f, includeDirectories));
                if (includeDirectories) {
                    result.add(f);
                }
            } else if (f.isFile()) {
                result.add(f);
            }
        }
        return result;
    }

    /**
     * Sometimes we want to publish an esa, product add on or similar with a license. We do this by
     * providing an accompany zip with its license.html files and a .properties file. This utility
     * method finds and explodes a .zip onto disk and pulls useful bits out.
     *
     * @param archiveFile - the .jar or .esa file to look for a sibling zip for
     * @return The artifact metadata from the sibling zip or <code>null</code> if none was found
     * @throws IOException
     * @throws RepositoryArchiveException
     */
    public ArtifactMetadata explodeArtifact(File archiveFile)
            throws RepositoryArchiveException {
        String path;
        try {
            path = archiveFile.getCanonicalPath();
        } catch (IOException e) {
            throw new RepositoryArchiveException(
                    "Failed to get the path for the archive", archiveFile, e);
        }
        String zipPath = path + ".metadata.zip";
        File zip = new File(zipPath);
        if (!zip.exists()) {
            return null;
        }

        return explodeZip(zip);

    }

    public ArtifactMetadata explodeZip(File zip)
            throws RepositoryArchiveException {
        ArtifactMetadata artifact = new ArtifactMetadata(zip);

        checkRequiredProperties(artifact);
        return artifact;

    }

    protected void checkRequiredProperties(ArtifactMetadata artifact)
            throws RepositoryArchiveInvalidEntryException {
        checkPropertySet(PROP_NAME, artifact);
        checkPropertySet(PROP_DESCRIPTION, artifact);
        checkPropertySet(PROP_SHORT_DESCRIPTION, artifact);
    }

    public void attachLicenseData(ArtifactMetadata licensedArtifact,
                                  MassiveResource resource) throws RepositoryException {
        for (File licenseFile : licensedArtifact.licenseFiles) {
            String licenseName = licenseFile.getName();
            licenseName = licenseName
                    .substring(0, licenseName.lastIndexOf("."));
            if (isLocale(licenseName)) {
                // You'd think there'd be a locale parser on Locale... there
                // isn't, sigh
                resource.addLicense(licenseFile,
                                    RepositoryUtils.localeForString(licenseName));
            } else {
                // Don't throw an exception: it may be something like
                // notices.html
            }
        }
        // Record the type of the license in the asset
        resource.setLicenseType(licensedArtifact.licenseType);
    }

    final private Set<String> languages = new HashSet<String>(
            Arrays.asList(Locale.getISOLanguages()));
    final private Set<String> countries = new HashSet<String>(
            Arrays.asList(Locale.getISOCountries()));

    public boolean isLocale(String localeText) {
        if (localeText.contains("_")) {
            // Language and country, test both
            String[] languageAndCountry = localeText.split("_");
            return languages.contains(languageAndCountry[0])
                   && countries.contains(languageAndCountry[1]);
        } else {
            // Language only
            return languages.contains(localeText);
        }
    }

    /**
     * Locate and process license agreement and information files within a feature
     *
     * @param esa
     * @param resource
     * @throws IOException
     */
    protected void processLAandLI(File archive, MassiveResource resource,
                                  EsaManifest feature) throws IOException,
            RepositoryException {
        String LAHeader = feature.getHeader(LA_HEADER_FEATURE);
        String LIHeader = feature.getHeader(LI_HEADER_FEATURE);
        processLAandLI(archive, resource, LAHeader, LIHeader);
    }

    /**
     * Locate and process license agreement and information files within a jar
     *
     * @param esa
     * @param resource
     * @throws IOException
     */
    protected void processLAandLI(File archive, MassiveResource resource,
                                  Manifest manifest) throws RepositoryException, IOException {
        Attributes attribs = manifest.getMainAttributes();
        String LAHeader = attribs.getValue(LA_HEADER_PRODUCT);
        String LIHeader = attribs.getValue(LI_HEADER_PRODUCT);
        processLAandLI(archive, resource, LAHeader, LIHeader);
    }

    /*
     * LAHeader typical value: "wlp/lafiles/LA" typical files names are of the form
     * "wlp/lafiles/LA_en"
     *
     * Both must be present unless GenerateEsas.specialFeatureTermsApply() which equates to
     * Subsystem-License: http://www.ibm.com/licenses/wlp-featureterms-v1 However by this point it's
     * very difficult to determine whether we're processing a resource that need only contain LA and
     * not LI files.
     */
    protected void processLAandLI(File archive, MassiveResource resource,
                                  String LAHeader, String LIHeader) throws IOException,
            RepositoryException {
        if (LAHeader == null && LIHeader == null) {
            return;
        }

        if (resource.getLicenseType() == LicenseType.UNSPECIFIED) {
            if (LAHeader == null || LIHeader != null) {
                throw new RepositoryException(
                        "New licenseTerms require LA and no LI. "
                                + archive.getCanonicalPath());
            }
        }

        // Note: we allow the user to upload a feature whose manifest has
        // an LA header but no LI header.

        File explodedEsa = unpackToTempDir(archive);
        String root = explodedEsa.getCanonicalPath();
        String laPrefix = (LAHeader == null) ? null : LAHeader
                .substring(LAHeader.lastIndexOf("/") + 1) + "_";
        String liPrefix = (LIHeader == null) ? null : LIHeader
                .substring(LIHeader.lastIndexOf("/") + 1) + "_";

        for (File f : listAllFiles(explodedEsa)) {
            String normalizedPath = f.getCanonicalPath()
                    .replaceAll("\\\\", "/");
            String shortPath = normalizedPath.substring(root.length());
            String fileName = f.getName();

            if (liPrefix != null && shortPath.contains(LIHeader)) {
                String localeText = fileName.substring(liPrefix.length());
                if (isLocale(localeText)) {
                    resource.addLicenseInformation(f,
                                                   RepositoryUtils.localeForString(localeText));
                }
            } else if (laPrefix != null && shortPath.contains(LAHeader)) {
                String localeText = fileName.substring(laPrefix.length());
                if (isLocale(localeText)) {
                    resource.addLicenseAgreement(f,
                                                 RepositoryUtils.localeForString(localeText));
                }
            }
        }
    }

    /**
     * <p>
     * This method adds an attachment for the content JAR and gets the location (if set) from the
     * artifact metadata, if the metadata is null or doesn't have a downloadURL in it then it will
     * upload the attachment.
     * </p>
     * <p>
     * If the asset already has a main content attachment then it is deleted (including deleting in
     * Massive) prior to adding the attachment as an asset can only have one content attachment
     * </p>
     *
     * @throws RepositoryException
     */
    protected void addContent(MassiveResource res, File assetFile, String name,
                              ArtifactMetadata metadata, String contentUrl) throws RepositoryException {
        String downloadUrl = contentUrl;
        String linkTypeString = null;
        if (metadata != null && metadata.properties != null) {
            if (downloadUrl == null) {
                downloadUrl = metadata.properties.getProperty(PROP_DOWNLOAD_URL);
            }
            linkTypeString = metadata.properties.getProperty(LINK_TYPE_PROPERTY_KEY);
        }

        /*
         * You can only add content once, if this hasn't been uploaded you are not allowed to call
         * any of the getXXXAttachment methods
         */
        String id = res.get_id();
        if (id != null && !id.isEmpty()) {
            AttachmentResource mainAttachmentResource = res.getMainAttachment();
            if (mainAttachmentResource != null
                && mainAttachmentResource.getType() == AttachmentType.CONTENT) {
                mainAttachmentResource.deleteNow();
            }
        }

        if (downloadUrl != null && !downloadUrl.isEmpty()) {
            AttachmentLinkType linkType = linkTypeString != null ? AttachmentLinkType
                    .valueOf(linkTypeString) : null;
            res.addContent(assetFile, name, downloadUrl, linkType);
        } else if (assetFile != null) {
            res.addContent(assetFile, name);
        } else {
            // No content so set the download policy to installer to hide the
            // download button in the UI
            res.setDownloadPolicy(DownloadPolicy.INSTALLER);
        }
    }

    public String getAppliesTo(ArtifactMetadata amd)
            throws RepositoryArchiveInvalidEntryException {

        // turn this
        // minVersion=8.5.5.0 ==> _appliesToMinimumVersionProp
        // minEdition= ==> _appliesToEditionsProp
        // into
        // Applies-To: com.ibm.websphere.appserver; productVersion=8.5.5.0+;
        // productEdition="BASE,DEVELOPERS,EXPRESS,ND,zOS"
        StringBuilder appliesToBuilder = new StringBuilder(
                "com.ibm.websphere.appserver");
        String minVersion = amd.getProperty(PROP_APPLIES_TO_MIN_VERSION);
        if (minVersion != null && !minVersion.isEmpty()) {
            appliesToBuilder.append("; productVersion=");
            appliesToBuilder.append(minVersion);
            appliesToBuilder.append("+");
        }
        String editonString = convertCommaSeparatedListToEditionString(amd
                .getProperty(PROP_APPLIES_TO_EDITIONS));
        if (editonString != null && !editonString.isEmpty()) {
            appliesToBuilder.append(editonString);
        }
        return appliesToBuilder.toString();
    }

    public void checkPropertySet(String propName, ArtifactMetadata amd)
            throws RepositoryArchiveInvalidEntryException {
        String prop = amd.getProperty(propName);
        if (prop == null) {
            throw new RepositoryArchiveInvalidEntryException("No " + propName
                                                             + " field was specified in the properties file",
                    amd.getArchive(), "*.properties");
        }
    }

    /**
     * Convert a comma separated list String of editions into the required format e.g. ";
     * productEdition=\"BASE,DEVELOPERS,EXPRESS,ND,zOS\"
     *
     * @param editions
     * @return String the product edition supported
     */
    private String convertCommaSeparatedListToEditionString(String csList) {

        if (csList == null) {
            return null;
        }

        // Split the comma separated list into an array
        csList = csList.trim();
        List<Edition> editions = new ArrayList<Edition>();
        if (csList.length() > 0) {
            String[] sArray = csList.split(",");
            for (String s : sArray) {
                if (s.length() != 0) {
                    editions.add(Edition.valueOf(s));
                }
            }
        }

        // convert the editions into a string
        String editionString = null;
        for (Edition ed : editions) {
            if (editionString == null) {
                editionString = "; productEdition=\"";
                editionString = editionString + ed.getProductEdition();
            } else {
                editionString = editionString + "," + ed.getProductEdition();
            }
        }
        if (editionString == null) {
            editionString = "";
        } else {
            editionString = editionString + "\"";
        }
        return editionString;
    }

    /**
     * Take the require.feature comma separated list and return a List of the entries
     *
     * @return List - the required features
     */
    public List<String> getRequiresFeature(ArtifactMetadata amd) {

        // Now check we have non-null input. I would like to check for valid
        // features
        // but as these may be user created I cannot find any way to do this so
        // it
        // is important there are no typos in the requires feature list TODO ?
        List<String> requiresFeature = new ArrayList<String>();
        String requiresFeatureProp = amd.getProperty(PROP_REQUIRE_FEATURE);
        if (requiresFeatureProp == null) {
            return null;
        }
        if (requiresFeatureProp.equals("")) {
            return requiresFeature;
        } else {
            String[] features = requiresFeatureProp.split("\\,");
            for (String feature : features) {
                requiresFeature.add(feature.trim());
            }
            return requiresFeature;
        }
    }

    /**
     * Process icons from the properties file
     *
     * @param amd Metadata
     * @param res Resource to add icons to
     * @throws RepositoryException
     */
    protected void processIcons(ArtifactMetadata amd, MassiveResource res)
            throws RepositoryException {
        int size = 0;
        String current = "";
        String sizeString = "";
        String iconName = "";
        ImageDetails details = null;
        String iconNames = amd.getIcons();

        if (iconNames != null) {
            iconNames = iconNames.replaceAll("\\s", "");

            StringTokenizer s = new StringTokenizer(iconNames, ",");
            while (s.hasMoreTokens()) {
                current = s.nextToken();

                if (current.contains(";")) { // if the icon has an associated
                                             // size
                    StringTokenizer t = new StringTokenizer(current, ";");
                    while (t.hasMoreTokens()) {
                        sizeString = t.nextToken();

                        if (sizeString.contains("size=")) {
                            String sizes[] = sizeString.split("size=");
                            size = Integer.parseInt(sizes[sizes.length - 1]);
                            details = new ImageDetails();
                            details.setWidth(size);
                            details.setHeight(size);
                        } else {
                            iconName = sizeString;
                        }
                    }

                } else {
                    iconName = current;
                }

                File icon = this.extractFileFromArchive(
                                                        amd.getArchive().getAbsolutePath(), iconName)
                        .getExtractedFile();
                if (icon.exists()) {
                    AttachmentResource at = res.addAttachment(icon,
                                                              AttachmentType.THUMBNAIL);
                    if (details != null) {
                        at.setImageDetails(details);
                    }
                } else {
                    throw new RepositoryArchiveEntryNotFoundException(
                            "Icon does not exist", amd.getArchive(), iconName);
                }
                details = null;
            }
        }
    }

    public String getProviderName(ArtifactMetadata amd) {
        return amd.getProperty(PROP_PROVIDER_NAME);
    }

    /**
     * Gets the following fields from the metadata and sets them on the resource: description,
     * shortDescription, name and url
     *
     * @param amd
     * @param resource
     */
    public void setCommonFieldsFromSideZip(ArtifactMetadata amd,
                                           MassiveResource resource) {
        resource.setDescription(amd.getLongDescription());
        resource.setShortDescription(amd.getShortDescription());
        resource.setName(amd.getName());
    }

    /**
     * Returns <code>true</code> if the supplied <code>file</code> is a valid zip and contains at
     * least one entry of each of the supplied <code>fileTypes</code>.
     *
     * @param file The zip file to check
     * @param fileTypes The types of files to look for
     * @return <code>true</code> if file types found
     */
    protected boolean doesZipContainFileTypes(File file, String... fileTypes) {
        // It must be a zip file with an XML file at it's root and a properties file in it
        if (!file.getName().endsWith(".zip")) {
            return false;
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(file);
            boolean[] foundFileTypes = new boolean[fileTypes.length];
            boolean foundAll = false;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements() && !foundAll) {
                foundAll = true;
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                for (int i = 0; i < fileTypes.length; i++) {
                    if (!foundFileTypes[i]) {
                        foundFileTypes[i] = name.endsWith(fileTypes[i]);
                        if (foundAll) {
                            foundAll = foundFileTypes[i];
                        }
                    }
                }
            }
            return foundAll;
        } catch (IOException e) {
            return false;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException e) {
                    return false;
                }
            }
        }
    }

    protected class ArtifactMetadata {
        public Collection<File> licenseFiles;
        private final Properties properties;
        private final Collection<File> otherFiles;
        private LicenseType licenseType;
        private final File archive;

        private ArtifactMetadata(File zip) throws RepositoryArchiveException {
            archive = zip;
            licenseFiles = new ArrayList<File>();
            properties = new Properties();
            otherFiles = new ArrayList<File>();

            // The artifact is the first file with a .artifactExtension suffix
            // in the tempDir
            // All licenses are in the 'lafiles' directory.
            // The first .properties file is the one we will parse
            // The long description can be stored in a separate description.html file

            File propertiesFile = null;
            File descriptionFile = null;
            File tempDir = null;
            try {
                tempDir = unpackToTempDir(zip);
            } catch (IOException ioe) {
                throw new RepositoryArchiveException(
                        "Failed to extract contents from archive", zip, ioe);
            }

            Collection<File> allTempFiles = listAllFiles(tempDir);

            // Just in case the ZIP contains more than one properties file always give preference to assetInfo.properties
            File assetInfo = new File(tempDir, "assetInfo.properties");
            if (assetInfo.exists()) {
                propertiesFile = assetInfo;
                allTempFiles.remove(assetInfo);
            }
            for (File f : allTempFiles) {
                if (f.isFile()) {
                    String name;
                    try {
                        name = f.getCanonicalPath();
                    } catch (IOException e) {
                        throw new RepositoryArchiveInvalidEntryException(
                                "Failed to read entry " + f.getAbsolutePath()
                                        + " in archive " + zip.getName(), zip,
                                f.getAbsolutePath(), e);
                    }
                    if (propertiesFile == null && name.endsWith(".properties")) {
                        propertiesFile = f;
                    } else if (descriptionFile == null
                               && f.getName().equalsIgnoreCase("description.html")) {
                        descriptionFile = f;
                    } else if (f.getParentFile().getName()
                            .equalsIgnoreCase("lafiles")) {
                        licenseFiles.add(f);
                    } else {
                        otherFiles.add(f);
                    }
                }
            }

            if (propertiesFile == null) {
                throw new RepositoryArchiveEntryNotFoundException(
                        "No properties file", zip, "*.properties");
            }

            // Determine license type
            FileReader propertiesReader = null;
            Reader descriptionReader = null;
            try {
                propertiesReader = new FileReader(propertiesFile);
                properties.load(propertiesReader);
                String licenseTypeInProps = properties
                        .getProperty(MassiveResource.LICENSE_TYPE.toString());

                // Not all artifacts have licenses so be lenient
                if (licenseTypeInProps != null) {
                    licenseType = LicenseType.valueOf(licenseTypeInProps);
                }

                // Now read in the long description
                if (descriptionFile != null) {
                    descriptionReader = new InputStreamReader(new FileInputStream(descriptionFile), "UTF-8");
                    char[] buf = new char[1024];
                    StringBuilder builder = new StringBuilder();
                    int chars;
                    while ((chars = descriptionReader.read(buf, 0, 1024)) != -1) { // read to EOF
                        builder.append(buf, 0, chars);
                    }
                    properties.setProperty(PROP_DESCRIPTION, builder.toString());
                }
            } catch (IOException iox) {
                throw new RepositoryArchiveIOException(
                        "Failed to read properties, licence and description from archive " + zip.getName(),
                        zip, iox);
            } finally {
                if (propertiesReader != null) {
                    try {
                        propertiesReader.close();
                    } catch (IOException e) {
                        throw new RepositoryArchiveIOException(
                                "Failed to close the file reader that was reading the properties in the archive"
                                        + zip.getName(), zip, e);
                    }
                }

                if (descriptionReader != null) {
                    try {
                        descriptionReader.close();
                    } catch (IOException e) {
                        throw new RepositoryArchiveIOException(
                                "Failed to close the file reader that was reading the description.html file"
                                        + zip.getName(), zip, e);
                    }
                }
            }
        }

        public String getName() {
            return properties.getProperty(PROP_NAME);
        }

        public String getShortDescription() {
            return properties.getProperty(PROP_SHORT_DESCRIPTION);
        }

        public String getLongDescription() {
            return properties.getProperty(PROP_DESCRIPTION);
        }

        public String getIcons() {
            return properties.getProperty(PROP_ICONS);
        }

        public File getArchive() {
            return archive;
        }

        // In case someone needs to get another, non common, property from the
        // props file
        public String getProperty(String propName) {
            return properties.getProperty(propName);
        }

        public Collection<File> getOtherFiles() {
            return otherFiles;
        }

        // return the first "other file" with the supplied extension eg ".zip" or null if not found
        public File getFileWithExtension(String ext) {
            File retFile = null;
            for (File f : otherFiles) {
                if (f.getName().endsWith(ext)) {
                    retFile = f;
                    break;
                }
            }
            return retFile;
        }
    }
}
