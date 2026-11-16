package org.springframework.web.multipart;
public class MaxUploadSizeExceededException extends RuntimeException {
    private final long maxUploadSize;
    public MaxUploadSizeExceededException(long maxUploadSize){super("Maximum upload size exceeded");this.maxUploadSize=maxUploadSize;}
    public long getMaxUploadSize(){return maxUploadSize;}
}
