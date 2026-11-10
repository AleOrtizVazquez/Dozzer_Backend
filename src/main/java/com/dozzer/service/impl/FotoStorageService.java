package com.dozzer.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

// Guarda las fotos de los bienes. Orden de preferencia:
//   1) Cloudinary (si están cloud-name, api-key y api-secret)  -> llave "cld:v<version>/<public_id>.<ext>"
//   2) Cloudflare R2 (si están sus 4 datos)
//   3) Carpeta local del servidor
// Las fotos de Cloudinary son públicas (URL imposible de adivinar) y se sirven desde su CDN,
// con miniatura automática por URL.
@Service
public class FotoStorageService {

    private static final Logger log = LoggerFactory.getLogger(FotoStorageService.class);
    private static final String PREFIJO_CLD = "cld:";
    private static final String MINI = "c_fill,w_240,h_240,g_auto,q_auto,f_auto";
    private static final String GRANDE = "c_limit,w_1280,q_auto,f_auto";

    @Value("${app.cloudinary.cloud-name:}") private String cldNube;
    @Value("${app.cloudinary.api-key:}") private String cldKey;
    @Value("${app.cloudinary.api-secret:}") private String cldSecret;
    @Value("${app.cloudinary.folder:dozzer}") private String cldCarpeta;

    @Value("${app.r2.account-id:}") private String accountId;
    @Value("${app.r2.access-key:}") private String accessKey;
    @Value("${app.r2.secret-key:}") private String secretKey;
    @Value("${app.r2.bucket:}") private String bucket;
    @Value("${app.fotos.carpeta-local:fotos-bienes}") private String carpetaLocal;
    // Solo para desarrollo: permite guardar en carpeta local si no hay Cloudinary ni R2.
    @Value("${app.fotos.permitir-local:false}") private boolean permitirLocal;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private S3Client r2;

    @PostConstruct
    void iniciar() {
        if (usaR2()) {
            r2 = S3Client.builder()
                    .endpointOverride(URI.create("https://" + accountId.trim() + ".r2.cloudflarestorage.com"))
                    .region(Region.of("auto"))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey.trim(), secretKey.trim())))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .chunkedEncodingEnabled(false)
                            .build())
                    .build();
        }
        if (usaCloudinary() || usaR2() || permitirLocal) {
            log.info("Fotos de bienes: se guardan en {}", destino());
        } else {
            log.warn("============================================================");
            log.warn("FOTOS DE BIENES SIN CONFIGURAR: faltan las variables de entorno");
            log.warn("CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY y CLOUDINARY_API_SECRET.");
            log.warn("No se aceptarán fotos hasta configurarlas y reiniciar el backend.");
            log.warn("============================================================");
        }
    }

    public boolean usaCloudinary() {
        return !vacio(cldNube) && !vacio(cldKey) && !vacio(cldSecret);
    }

    public boolean usaR2() {
        return !vacio(accountId) && !vacio(accessKey) && !vacio(secretKey) && !vacio(bucket);
    }

    public String destino() {
        if (usaCloudinary()) return "Cloudinary (carpeta " + carpeta() + ")";
        if (usaR2()) return "Cloudflare R2";
        return permitirLocal ? "carpeta local del servidor" : "SIN CONFIGURAR";
    }

    // Guarda la foto y regresa la llave que hay que anotar en el bien.
    // "nombre" es relativo y sin extensión, p. ej. "bienes/123-ab12cd34".
    public String guardar(String nombre, byte[] datos, String tipo) {
        String ext = tipo != null && tipo.contains("png") ? "png" : tipo != null && tipo.contains("webp") ? "webp" : "jpg";
        try {
            if (usaCloudinary()) return subirCloudinary(nombre, datos, tipo);
            if (!usaR2() && !permitirLocal) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "El almacenamiento de fotos no está configurado; avisa al administrador.");
            }
            String llave = nombre + "." + ext;
            if (usaR2()) {
                r2.putObject(PutObjectRequest.builder().bucket(bucket.trim()).key(llave).contentType(tipo).build(),
                        RequestBody.fromBytes(datos));
            } else {
                Path archivo = rutaLocal(llave);
                Files.createDirectories(archivo.getParent());
                Files.write(archivo, datos);
            }
            return llave;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("No se pudo guardar la foto {}: {}", nombre, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No fue posible guardar la foto. Inténtalo de nuevo.");
        }
    }

    public byte[] leer(String llave) {
        try {
            if (esCloudinary(llave)) {
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(urlGrande(llave)))
                        .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
                return r.body();
            }
            if (usaR2()) {
                return r2.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket.trim()).key(llave).build()).asByteArray();
            }
            return Files.readAllBytes(rutaLocal(llave));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Foto no encontrada");
        }
    }

    // Borra la foto. Regresa false si no se pudo (no lanza excepción para no frenar otros borrados).
    public boolean borrar(String llave) {
        if (llave == null || llave.isBlank()) return true;
        try {
            if (esCloudinary(llave)) {
                if (!usaCloudinary()) return false;
                Map<String, String> p = new TreeMap<>();
                p.put("public_id", publicId(llave));
                p.put("invalidate", "true");
                p.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
                JsonNode r = llamarApi("image/destroy", p);
                String res = r.path("result").asText();
                return "ok".equals(res) || "not found".equals(res);
            }
            if (usaR2()) {
                r2.deleteObject(DeleteObjectRequest.builder().bucket(bucket.trim()).key(llave).build());
                return true;
            }
            Files.deleteIfExists(rutaLocal(llave));
            return true;
        } catch (Exception e) {
            log.warn("No se pudo borrar la foto {}: {}", llave, e.getMessage());
            return false;
        }
    }

    // URL pública de la miniatura (solo Cloudinary). null si la foto está en otro almacén.
    public String urlMini(String llave) {
        return esCloudinary(llave) && !vacio(cldNube) ? urlCloudinary(llave, MINI) : null;
    }

    // URL pública de la foto grande (solo Cloudinary). null si la foto está en otro almacén.
    public String urlGrande(String llave) {
        return esCloudinary(llave) && !vacio(cldNube) ? urlCloudinary(llave, GRANDE) : null;
    }

    // ===================== Cloudinary =====================

    private String subirCloudinary(String nombre, byte[] datos, String tipo) throws Exception {
        String publicId = carpeta() + "/" + nombre;
        int barra = publicId.lastIndexOf('/');
        Map<String, String> p = new TreeMap<>();
        p.put("public_id", publicId);
        p.put("asset_folder", publicId.substring(0, barra));
        p.put("overwrite", "true");
        p.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        String mime = tipo != null && tipo.startsWith("image/") ? tipo : "image/jpeg";
        p.put("file", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(datos));
        JsonNode r = llamarApi("image/upload", p);
        if (!r.hasNonNull("public_id")) {
            throw new IOException(r.path("error").path("message").asText("respuesta sin public_id"));
        }
        return PREFIJO_CLD + "v" + r.path("version").asText() + "/" + r.path("public_id").asText()
                + "." + r.path("format").asText("jpg");
    }

    // Firma la petición (SHA-1 de los parámetros ordenados + secreto) y la manda a la API de Cloudinary.
    private JsonNode llamarApi(String ruta, Map<String, String> params) throws Exception {
        String aFirmar = params.entrySet().stream()
                .filter(e -> !e.getKey().equals("file"))
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        String firma = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest((aFirmar + cldSecret.trim()).getBytes(StandardCharsets.UTF_8)));
        Map<String, String> todo = new TreeMap<>(params);
        todo.put("api_key", cldKey.trim());
        todo.put("signature", firma);
        String cuerpo = todo.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.cloudinary.com/v1_1/" + cldNube.trim() + "/" + ruta))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode r = json.readTree(resp.body());
        if (resp.statusCode() >= 300) {
            throw new IOException("Cloudinary " + resp.statusCode() + ": " + r.path("error").path("message").asText());
        }
        return r;
    }

    private String urlCloudinary(String llave, String transformacion) {
        String nube = vacio(cldNube) ? "" : cldNube.trim();
        return "https://res.cloudinary.com/" + nube + "/image/upload/" + transformacion + "/" + llave.substring(PREFIJO_CLD.length());
    }

    // "cld:v123/dozzer/bienes/5-ab.jpg" -> "dozzer/bienes/5-ab"
    private static String publicId(String llave) {
        String s = llave.substring(PREFIJO_CLD.length());
        s = s.substring(s.indexOf('/') + 1);
        int punto = s.lastIndexOf('.');
        return punto > 0 ? s.substring(0, punto) : s;
    }

    private String carpeta() {
        String c = vacio(cldCarpeta) ? "dozzer" : cldCarpeta.trim();
        return c.replaceAll("^/+|/+$", "");
    }

    private static boolean esCloudinary(String llave) {
        return llave != null && llave.startsWith(PREFIJO_CLD);
    }

    // ===================== Local =====================

    private Path rutaLocal(String llave) throws IOException {
        Path base = Paths.get(carpetaLocal).toAbsolutePath().normalize();
        Path archivo = base.resolve(llave).normalize();
        if (!archivo.startsWith(base)) throw new IOException("Ruta no válida");
        return archivo;
    }

    private static boolean vacio(String v) {
        return v == null || v.isBlank();
    }
}
