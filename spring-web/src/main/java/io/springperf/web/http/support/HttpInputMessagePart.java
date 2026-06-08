package io.springperf.web.http.support;

/**
 * Representation of a single part in a multipart request.
 *
 * <p>Extends {@link BodyHttpInputMessage} with metadata specific to
 * multipart form data: the part name, content size, and original filename.
 * Each part is an independent HTTP message with its own headers and body.</p>
 *
 * @since 1.0.0
 * @see BodyHttpInputMessage
 * @see org.springframework.web.multipart.MultipartFile
 */
public interface HttpInputMessagePart extends BodyHttpInputMessage {

    /**
     * Return the name of this part (from the {@code name} attribute).
     *
     * @return the part name
     */
    String getName();

    /**
     * Return the content size of this part in bytes.
     *
     * @return the part size
     */
    long getSize();

    /**
     * Return the original filename submitted by the client.
     *
     * @return the submitted filename, or {@code null} if not a file upload
     */
    String getSubmittedFileName();
}
