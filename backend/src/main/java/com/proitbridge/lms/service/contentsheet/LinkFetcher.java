package com.proitbridge.lms.service.contentsheet;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetching a file somebody linked to in the content sheet.
 *
 * The server is about to make requests to addresses typed into a spreadsheet, which is
 * the textbook server side request forgery: a link to 169.254.169.254 or localhost:27017
 * would have the LMS read its own cloud metadata or database port and store the answer
 * as a "dataset". So every hop, redirects included, must be https to a public address;
 * redirects are followed by hand so each one is checked; the body is read with a byte
 * ceiling; and nothing here ever sends a credential.
 *
 * Google links are rewritten to the address that returns the bytes. A Drive "view" page,
 * a Doc, a Slides deck and a Sheet each have a different export form, and a file that
 * is not shared publicly answers with a sign in page, which is reported as exactly that
 * rather than stored as an HTML "PDF".
 */
@Component
public class LinkFetcher {

    /** A message fit to show an admin beside the row that caused it. */
    public static class FetchException extends Exception {
        public FetchException(String message) { super(message); }
    }

    public record Fetched(String filename, String contentType, byte[] bytes) {}

    private static final int MAX_REDIRECTS = 6;

    private static final Pattern DOC = Pattern.compile("docs\\.google\\.com/document/d/([\\w-]{20,})");
    private static final Pattern SLIDES = Pattern.compile("docs\\.google\\.com/presentation/d/([\\w-]{20,})");
    private static final Pattern SHEET = Pattern.compile("docs\\.google\\.com/spreadsheets/d/([\\w-]{20,})");
    private static final List<Pattern> DRIVE_FILE = List.of(
            Pattern.compile("drive\\.google\\.com/file/d/([\\w-]{20,})"),
            Pattern.compile("drive\\.google\\.com/(?:open|uc)\\?(?:.*&)?id=([\\w-]{20,})"),
            Pattern.compile("docs\\.google\\.com/file/d/([\\w-]{20,})"));
    private static final Pattern DRIVE_FOLDER =
            Pattern.compile("drive\\.google\\.com/(?:drive/)?(?:u/\\d+/)?folders/");

    private static final Map<String, String> EXT_FOR_TYPE = Map.of(
            "application/pdf", "pdf",
            "text/csv", "csv",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx",
            "application/json", "json",
            "text/plain", "txt",
            "application/zip", "zip");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /* ------------------------------------------------------------- addresses */

    /**
     * The address that answers with the file itself. Throws for a link that can never
     * be a file, such as a Drive folder.
     */
    public String downloadUrlFor(String link) throws FetchException {
        String raw = link.trim();
        if (DRIVE_FOLDER.matcher(raw).find()) {
            throw new FetchException("That is a Drive folder. Link the file itself.");
        }
        Matcher m;
        if ((m = DOC.matcher(raw)).find()) {
            return "https://docs.google.com/document/d/" + m.group(1) + "/export?format=pdf";
        }
        if ((m = SLIDES.matcher(raw)).find()) {
            return "https://docs.google.com/presentation/d/" + m.group(1) + "/export/pptx";
        }
        if ((m = SHEET.matcher(raw)).find()) {
            return "https://docs.google.com/spreadsheets/d/" + m.group(1) + "/export?format=xlsx";
        }
        for (Pattern p : DRIVE_FILE) {
            if ((m = p.matcher(raw)).find()) {
                /* confirm=t skips the "too large to scan for viruses" page on big files */
                return "https://drive.usercontent.google.com/download?id=" + m.group(1)
                        + "&export=download&confirm=t";
            }
        }
        if (raw.contains("dropbox.com/") && raw.contains("dl=0")) return raw.replace("dl=0", "dl=1");
        return raw;
    }

    /** The master sheet: a Google Sheet is exported as xlsx; anything else is read as is. */
    public String sheetUrlFor(String link) throws FetchException {
        Matcher m = SHEET.matcher(link.trim());
        if (m.find()) {
            return "https://docs.google.com/spreadsheets/d/" + m.group(1) + "/export?format=xlsx";
        }
        return downloadUrlFor(link);
    }

    /**
     * Every hop of every request goes through this: https only, and a host that
     * resolves to no private, loopback, link local or multicast address.
     */
    URI checkedUri(String address) throws FetchException {
        String a = address.trim();
        if (a.regionMatches(true, 0, "http://", 0, 7)) a = "https://" + a.substring(7);
        URI uri;
        try {
            uri = URI.create(a);
        } catch (IllegalArgumentException e) {
            throw new FetchException("That is not a valid link.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new FetchException("Only https links can be fetched.");
        }
        if (uri.getUserInfo() != null) {
            throw new FetchException("A link with a user name or password in it is refused.");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new FetchException("The site " + uri.getHost() + " does not exist (broken link).");
        }
        for (InetAddress ip : addresses) {
            if (ip.isLoopbackAddress() || ip.isAnyLocalAddress() || ip.isLinkLocalAddress()
                    || ip.isSiteLocalAddress() || ip.isMulticastAddress() || uniqueLocal(ip)) {
                throw new FetchException("That link points inside a private network, which is refused.");
            }
        }
        return uri;
    }

    private static boolean uniqueLocal(InetAddress ip) {
        /* fc00::/7, IPv6's private range, which isSiteLocalAddress does not cover */
        return ip instanceof Inet6Address && (ip.getAddress()[0] & 0xfe) == 0xfc;
    }

    /* ------------------------------------------------------------- fetching */

    /** A file from a material cell. */
    public Fetched fetchFile(String link, long maxBytes) throws FetchException {
        Fetched f = get(downloadUrlFor(link), maxBytes);
        if (f.contentType() != null && f.contentType().toLowerCase(Locale.ROOT).startsWith("text/html")) {
            throw new FetchException("The link opened a web page, not a file. If it is on Drive, "
                    + "share it as \"Anyone with the link\" and link the file itself.");
        }
        return f;
    }

    /** The master sheet. */
    public Fetched fetchSheet(String link, long maxBytes) throws FetchException {
        Fetched f = get(sheetUrlFor(link), maxBytes);
        if (f.contentType() != null && f.contentType().toLowerCase(Locale.ROOT).startsWith("text/html")) {
            throw new FetchException("The sheet link opened a sign in page, not the sheet. Share the "
                    + "Google Sheet as \"Anyone with the link can view\".");
        }
        return f;
    }

    private Fetched get(String address, long maxBytes) throws FetchException {
        URI uri = checkedUri(address);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(120))
                    .header("User-Agent", "Mozilla/5.0 (compatible; LMS content sync)")
                    .GET().build();
            HttpResponse<InputStream> res;
            try {
                res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new FetchException("The site did not answer (" + e.getClass().getSimpleName() + ").");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException("The download was interrupted.");
            }
            int code = res.statusCode();
            if (code >= 300 && code < 400) {
                closeQuietly(res.body());
                String location = res.headers().firstValue("Location").orElse(null);
                if (location == null) throw new FetchException("The site redirected nowhere.");
                if (uri.resolve(location).getHost() != null
                        && uri.resolve(location).getHost().endsWith("accounts.google.com")) {
                    throw new FetchException("Google asked for a sign in. Share the file as "
                            + "\"Anyone with the link\".");
                }
                uri = checkedUri(uri.resolve(location).toString());
                continue;
            }
            if (code != 200) {
                closeQuietly(res.body());
                throw new FetchException(switch (code) {
                    case 401, 403 -> "Access was refused. Share it as \"Anyone with the link\".";
                    case 404, 410 -> "Nothing is at that link any more (broken link).";
                    case 429 -> "The site is rate limiting downloads. Retry later.";
                    default -> "The site answered with an error (HTTP " + code + ").";
                });
            }
            byte[] bytes = readCapped(res.body(), maxBytes);
            String type = res.headers().firstValue("Content-Type").orElse("");
            String name = filenameFrom(res.headers().firstValue("Content-Disposition").orElse(null), uri, type);
            return new Fetched(name, type, bytes);
        }
        throw new FetchException("Too many redirects.");
    }

    private static byte[] readCapped(InputStream in, long maxBytes) throws FetchException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new FetchException("The file is larger than " + (maxBytes / (1024 * 1024))
                            + " MB, the limit for a file.");
                }
                out.write(buf, 0, n);
            }
            if (total == 0) throw new FetchException("The link returned an empty file.");
            return out.toByteArray();
        } catch (IOException e) {
            throw new FetchException("The download broke off part way.");
        }
    }

    private static void closeQuietly(InputStream in) {
        try { in.close(); } catch (IOException ignored) { }
    }

    private static final Pattern CD_STAR = Pattern.compile("filename\\*=(?:UTF-8|utf-8)''([^;]+)");
    private static final Pattern CD_QUOTED = Pattern.compile("filename=\"([^\"]+)\"");
    private static final Pattern CD_BARE = Pattern.compile("filename=([^;]+)");

    static String filenameFrom(String disposition, URI uri, String contentType) {
        String name = null;
        if (disposition != null) {
            Matcher m;
            if ((m = CD_STAR.matcher(disposition)).find()) {
                name = URLDecoder.decode(m.group(1).trim(), StandardCharsets.UTF_8);
            } else if ((m = CD_QUOTED.matcher(disposition)).find()) {
                name = m.group(1).trim();
            } else if ((m = CD_BARE.matcher(disposition)).find()) {
                name = m.group(1).trim();
            }
        }
        if (name == null || name.isBlank()) {
            String path = uri.getPath() == null ? "" : uri.getPath();
            String last = path.substring(path.lastIndexOf('/') + 1);
            name = URLDecoder.decode(last, StandardCharsets.UTF_8);
        }
        if (name.isBlank() || "download".equals(name) || "export".equals(name)) name = "material";
        if (!name.contains(".")) {
            String base = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
            String ext = EXT_FOR_TYPE.get(base);
            if (ext != null) name = name + "." + ext;
        }
        return name;
    }

    /* ------------------------------------------------------------- videos */

    /**
     * Whether a YouTube video exists and may be embedded, asked of YouTube's public
     * oEmbed endpoint. "OK", "MISSING", "NOT_EMBEDDABLE", or null when YouTube could not
     * be asked, which is never treated as a failure: a network blip is not a broken link.
     */
    public String youtubeStatus(String videoId) {
        try {
            String watch = "https://www.youtube.com/watch?v=" + videoId;
            URI uri = URI.create("https://www.youtube.com/oembed?format=json&url="
                    + URLEncoder.encode(watch, StandardCharsets.UTF_8));
            HttpResponse<Void> res = http.send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.discarding());
            int code = res.statusCode();
            if (code == 200) return "OK";
            if (code == 401 || code == 403) return "NOT_EMBEDDABLE";
            if (code == 400 || code == 404) return "MISSING";
            return null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
