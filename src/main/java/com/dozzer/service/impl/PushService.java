package com.dozzer.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

// Notificaciones al celular (Web Push, RFC 8030/8291/8292) sin librerías externas.
// Las llaves VAPID se toman de app.push.* si existen; si no, se generan una vez y se guardan en push_config.
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    private static final ECParameterSpec P256 = parametrosP256();
    private static final SecureRandom AZAR = new SecureRandom();
    // Solo se envía a los servicios de push de los navegadores (evita que alguien use el servidor para llamar a otras direcciones)
    private static final List<String> SERVICIOS = List.of(".googleapis.com", ".mozilla.com", ".push.apple.com", ".notify.windows.com");

    @Autowired private JdbcTemplate jdbc;

    @Value("${app.push.public-key:}") private String clavePublicaConfig;
    @Value("${app.push.private-key:}") private String clavePrivadaConfig;
    @Value("${app.push.subject:mailto:sistemas@dozzer.mx}") private String sujeto;

    private volatile ECPrivateKey privada;
    private volatile String publicaB64;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService envios = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "push-envio");
        t.setDaemon(true);
        return t;
    });

    // ===================== Suscripciones =====================

    public String clavePublica() {
        asegurarLlaves();
        return publicaB64;
    }

    public void suscribir(Long idUsuario, String endpoint, String p256dh, String auth, String agente) {
        validarEndpoint(endpoint);
        if (p256dh == null || p256dh.isBlank() || p256dh.length() > 200 || auth == null || auth.isBlank() || auth.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos de notificación incompletos");
        }
        if (decodificar(p256dh).length != 65 || decodificar(auth).length != 16) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos de notificación no válidos");
        }
        String ag = agente == null ? null : agente.substring(0, Math.min(300, agente.length()));
        // El mismo celular puede cambiar de usuario (otra sesión): la suscripción pasa al usuario actual
        jdbc.update("insert into push_suscripciones (id_usuario, endpoint, p256dh, auth, agente, fecha) values (?, ?, ?, ?, ?, now()) " +
                "on conflict (endpoint) do update set id_usuario = excluded.id_usuario, p256dh = excluded.p256dh, " +
                "auth = excluded.auth, agente = excluded.agente, fecha = now()", idUsuario, endpoint, p256dh, auth, ag);
    }

    public void quitar(Long idUsuario, String endpoint) {
        if (endpoint == null) return;
        jdbc.update("delete from push_suscripciones where endpoint = ? and id_usuario = ?", endpoint, idUsuario);
    }

    public int contar(Long idUsuario) {
        Integer n = jdbc.queryForObject("select count(*) from push_suscripciones where id_usuario = ?", Integer.class, idUsuario);
        return n == null ? 0 : n;
    }

    // ===================== Envío =====================

    // Se envía cuando la transacción termina bien (si se cancela, no se avisa nada) y en segundo plano
    public void enviarDespues(Collection<Long> idsUsuarios, String titulo, String cuerpo, String url, String etiqueta) {
        Set<Long> destino = idsUsuarios == null ? Set.of()
                : idsUsuarios.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (destino.isEmpty()) return;
        Runnable tarea = () -> envios.submit(() -> enviarAhora(destino, titulo, cuerpo, url, etiqueta));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { tarea.run(); }
            });
        } else {
            tarea.run();
        }
    }

    // Devuelve cuántos celulares recibieron el aviso
    public int enviarAhora(Set<Long> idsUsuarios, String titulo, String cuerpo, String url, String etiqueta) {
        if (idsUsuarios.isEmpty()) return 0;
        try {
            asegurarLlaves();
            Map<String, Object> datos = new LinkedHashMap<>();
            datos.put("titulo", titulo);
            datos.put("cuerpo", cuerpo);
            datos.put("url", url == null ? "/" : url);
            datos.put("etiqueta", etiqueta == null ? "dozzer" : etiqueta);
            byte[] mensaje = json.writeValueAsBytes(datos);
            String marcas = idsUsuarios.stream().map(x -> "?").collect(Collectors.joining(","));
            List<Map<String, Object>> subs = jdbc.queryForList(
                    "select id_suscripcion, endpoint, p256dh, auth from push_suscripciones where id_usuario in (" + marcas + ")",
                    idsUsuarios.toArray());
            int ok = 0;
            for (Map<String, Object> s : subs) {
                try {
                    int estado = enviar((String) s.get("endpoint"), (String) s.get("p256dh"), (String) s.get("auth"), mensaje);
                    if (estado == 404 || estado == 410) {
                        // El celular quitó el permiso o desinstaló: ya no sirve
                        jdbc.update("delete from push_suscripciones where id_suscripcion = ?", s.get("id_suscripcion"));
                    } else if (estado >= 200 && estado < 300) {
                        ok++;
                    } else {
                        log.warn("Notificación no aceptada por el servicio de push (HTTP {})", estado);
                    }
                } catch (Exception e) {
                    log.warn("No se pudo enviar una notificación: {}", e.getMessage());
                }
            }
            return ok;
        } catch (Exception e) {
            log.warn("No se pudieron enviar notificaciones: {}", e.getMessage());
            return 0;
        }
    }

    private int enviar(String endpoint, String p256dh, String auth, byte[] mensaje) throws Exception {
        validarEndpoint(endpoint);
        KeyPair efimera = generarPar();
        byte[] sal = new byte[16];
        AZAR.nextBytes(sal);
        byte[] cuerpo = cifrar(mensaje, decodificar(p256dh), decodificar(auth), efimera, sal);
        URI uri = URI.create(endpoint);
        String audiencia = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("TTL", "86400")
                .header("Urgency", "high")
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("Authorization", "vapid t=" + jwtVapid(audiencia) + ", k=" + publicaB64)
                .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    // ===================== Criptografía =====================

    // Cifrado "aes128gcm" de RFC 8291 (un solo registro)
    public static byte[] cifrar(byte[] mensaje, byte[] uaPublica, byte[] secretoAuth, KeyPair efimera, byte[] sal) throws Exception {
        PublicKey ua = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(punto(uaPublica), P256));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(efimera.getPrivate());
        ka.doPhase(ua, true);
        byte[] ecdh = ka.generateSecret();
        byte[] asPublica = sinComprimir(((ECPublicKey) efimera.getPublic()).getW());

        byte[] prkLlave = hmac(secretoAuth, ecdh);
        byte[] ikm = hmac(prkLlave, unir("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0}, uaPublica, asPublica, new byte[]{1}));
        byte[] prk = hmac(sal, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, unir("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, unir("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1})), 12);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] cifrado = c.doFinal(unir(mensaje, new byte[]{2}));

        ByteBuffer bb = ByteBuffer.allocate(16 + 4 + 1 + asPublica.length + cifrado.length);
        bb.put(sal).putInt(4096).put((byte) asPublica.length).put(asPublica).put(cifrado);
        return bb.array();
    }

    // JWT ES256 de VAPID (RFC 8292)
    private String jwtVapid(String audiencia) throws Exception {
        String cabecera = codificar("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        long exp = Instant.now().getEpochSecond() + 12 * 3600;
        String datos = codificar(json.writeValueAsBytes(Map.of("aud", audiencia, "exp", exp, "sub", sujeto)));
        Signature firma = Signature.getInstance("SHA256withECDSAinP1363Format");
        firma.initSign(privada);
        firma.update((cabecera + "." + datos).getBytes(StandardCharsets.US_ASCII));
        return cabecera + "." + datos + "." + codificar(firma.sign());
    }

    private synchronized void asegurarLlaves() {
        if (privada != null) return;
        try {
            String pub = clavePublicaConfig == null ? "" : clavePublicaConfig.trim();
            String priv = clavePrivadaConfig == null ? "" : clavePrivadaConfig.trim();
            if (pub.isEmpty() || priv.isEmpty()) {
                List<Map<String, Object>> filas = jdbc.queryForList("select clave_publica, clave_privada from push_config where id = 1");
                if (filas.isEmpty()) {
                    KeyPair par = generarPar();
                    jdbc.update("insert into push_config (id, clave_publica, clave_privada) values (1, ?, ?) on conflict (id) do nothing",
                            codificar(sinComprimir(((ECPublicKey) par.getPublic()).getW())),
                            codificar(entero32(((ECPrivateKey) par.getPrivate()).getS())));
                    filas = jdbc.queryForList("select clave_publica, clave_privada from push_config where id = 1");
                    log.info("Notificaciones: se generaron las llaves del servidor (quedan guardadas en la base)");
                }
                pub = (String) filas.get(0).get("clave_publica");
                priv = (String) filas.get(0).get("clave_privada");
            }
            privada = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, decodificar(priv)), P256));
            publicaB64 = pub;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudieron preparar las llaves de notificaciones", e);
        }
    }

    private static void validarEndpoint(String endpoint) {
        try {
            URI u = URI.create(endpoint);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (endpoint.length() > 1000 || !"https".equals(u.getScheme()) || SERVICIOS.stream().noneMatch(host::endsWith)) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dirección de notificación no válida");
        }
    }

    private static KeyPair generarPar() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private static ECParameterSpec parametrosP256() {
        try {
            AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
            p.init(new ECGenParameterSpec("secp256r1"));
            return p.getParameterSpec(ECParameterSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ECPoint punto(byte[] b) {
        if (b.length != 65 || b[0] != 4) throw new IllegalArgumentException("Llave pública no válida");
        return new ECPoint(new BigInteger(1, Arrays.copyOfRange(b, 1, 33)), new BigInteger(1, Arrays.copyOfRange(b, 33, 65)));
    }

    public static byte[] sinComprimir(ECPoint p) {
        return unir(new byte[]{4}, entero32(p.getAffineX()), entero32(p.getAffineY()));
    }

    private static byte[] entero32(BigInteger n) {
        byte[] b = n.toByteArray();
        if (b.length == 32) return b;
        byte[] r = new byte[32];
        if (b.length > 32) System.arraycopy(b, b.length - 32, r, 0, 32);
        else System.arraycopy(b, 0, r, 32 - b.length, b.length);
        return r;
    }

    private static byte[] hmac(byte[] llave, byte[] datos) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(llave, "HmacSHA256"));
        return m.doFinal(datos);
    }

    private static byte[] unir(byte[]... partes) {
        int n = 0;
        for (byte[] p : partes) n += p.length;
        ByteBuffer bb = ByteBuffer.allocate(n);
        for (byte[] p : partes) bb.put(p);
        return bb.array();
    }

    public static String codificar(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static byte[] decodificar(String s) {
        return Base64.getUrlDecoder().decode(s.trim().replace('+', '-').replace('/', '_').replace("=", ""));
    }
}
