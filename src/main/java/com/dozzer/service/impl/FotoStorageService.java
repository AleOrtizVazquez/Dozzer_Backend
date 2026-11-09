package com.dozzer.service.impl;

import jakarta.annotation.PostConstruct;
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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// Guarda las fotos de los bienes.
// Si hay credenciales de Cloudflare R2 se usan; si no, se guardan en una carpeta local del servidor.
@Service
public class FotoStorageService {

    @Value("${app.r2.account-id:}") private String accountId;
    @Value("${app.r2.access-key:}") private String accessKey;
    @Value("${app.r2.secret-key:}") private String secretKey;
    @Value("${app.r2.bucket:}") private String bucket;
    @Value("${app.fotos.carpeta-local:fotos-bienes}") private String carpetaLocal;

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
    }

    public boolean usaR2() {
        return !vacio(accountId) && !vacio(accessKey) && !vacio(secretKey) && !vacio(bucket);
    }

    public String destino() {
        return usaR2() ? "Cloudflare R2" : "carpeta local del servidor";
    }

    public void guardar(String llave, byte[] datos, String tipo) {
        try {
            if (usaR2()) {
                r2.putObject(PutObjectRequest.builder().bucket(bucket.trim()).key(llave).contentType(tipo).build(),
                        RequestBody.fromBytes(datos));
            } else {
                Path archivo = rutaLocal(llave);
                Files.createDirectories(archivo.getParent());
                Files.write(archivo, datos);
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No fue posible guardar la foto. Inténtalo de nuevo.");
        }
    }

    public byte[] leer(String llave) {
        try {
            if (usaR2()) {
                return r2.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket.trim()).key(llave).build()).asByteArray();
            }
            return Files.readAllBytes(rutaLocal(llave));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Foto no encontrada");
        }
    }

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
