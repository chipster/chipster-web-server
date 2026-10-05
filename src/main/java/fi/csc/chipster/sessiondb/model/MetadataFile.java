package fi.csc.chipster.sessiondb.model;

import jakarta.persistence.Lob;

import fi.csc.chipster.rest.hibernate.DeepCopyable;

public class MetadataFile implements DeepCopyable {

    public static final String METADATA_FILE_LIST_JSON_TYPE = "MetadataFileListJsonType";

    public static final String APPLICATION_VERSIONS_NAME = "application-versions.json";

    /**
     * Job metadata files are written to the job's working directory. Allow only
     * phenodata files, because any other name there could replace a file that
     * the job reads: a Python module, an input dataset, or the chipster-inputs.tsv
     * that comp writes for the tool.
     *
     * The META inputs of the tools and the client agree on the phenodata prefix.
     */
    private static final String JOB_INPUT_NAME_REGEX = "^phenodata[\\w\\-\\.]*\\.tsv$";

    private String name;
    @Lob
    private String content;

    public MetadataFile() {
    }

    public MetadataFile(String name, String content) {
        this.name = name;
        this.content = content;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    /**
     * Check the name of a metadata file that comp writes to the job's working
     * directory
     */
    public static boolean isValidJobInputName(String name) {
        return name != null && name.matches(JOB_INPUT_NAME_REGEX);
    }

    /**
     * Check the name of a metadata file in a job, including the application
     * versions file, which comp adds to the job after it has run
     */
    public static boolean isValidJobName(String name) {
        return isValidJobInputName(name) || APPLICATION_VERSIONS_NAME.equals(name);
    }

    @Override
    public Object deepCopy() {
        MetadataFile f = new MetadataFile();
        f.name = name;
        f.content = content;
        return f;
    }

}
