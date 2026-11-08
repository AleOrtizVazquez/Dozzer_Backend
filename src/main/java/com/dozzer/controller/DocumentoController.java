package com.dozzer.controller;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.regex.Pattern;

// Envío por correo de los formatos de Alta/Baja (el PDF se genera en el navegador)
@RestController
@RequestMapping("/api/documentos")
@PreAuthorize("hasRole('ADMIN')")
public class DocumentoController {

    private static final Pattern CORREO = Pattern.compile("^[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+$");
    private static final int MAX_DESTINATARIOS = 5;
    private static final int MAX_PDF_BYTES = 8 * 1024 * 1024;

    @Autowired
    private ObjectProvider<JavaMailSender> mailSender;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${spring.mail.username:}")
    private String mailUsuario;

    @Value("${spring.mail.password:}")
    private String mailPassword;

    @Value("${app.mail.from:}")
    private String mailRemitente;

    // Listo para enviar solo si hay servidor, usuario y contraseña
    private boolean correoConfigurado() {
        return mailHost != null && !mailHost.isBlank()
                && mailUsuario != null && !mailUsuario.isBlank()
                && mailPassword != null && !mailPassword.isBlank()
                && mailSender.getIfAvailable() != null;
    }

    @GetMapping("/correo")
    public Map<String, Object> estadoCorreo() {
        return Map.of("configurado", correoConfigurado());
    }

    @PostMapping("/enviar")
    public Map<String, Object> enviar(@RequestBody Map<String, String> datos) {
        if (!correoConfigurado()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El envío de correo aún no está configurado en el servidor");
        }

        List<String> destinatarios = Arrays.stream(valor(datos, "para").split("[,;\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (destinatarios.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe al menos un correo destinatario");
        }
        if (destinatarios.size() > MAX_DESTINATARIOS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Máximo " + MAX_DESTINATARIOS + " destinatarios por envío");
        }
        for (String d : destinatarios) {
            if (d.length() > 180 || !CORREO.matcher(d).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Correo no válido: " + d);
            }
        }

        byte[] pdf;
        try {
            pdf = Base64.getDecoder().decode(valor(datos, "pdfBase64"));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El archivo PDF no es válido");
        }
        if (pdf.length < 5 || pdf.length > MAX_PDF_BYTES
                || !(pdf[0] == '%' && pdf[1] == 'P' && pdf[2] == 'D' && pdf[3] == 'F')) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El archivo PDF no es válido");
        }

        String archivo = valor(datos, "nombreArchivo").replaceAll("[^A-Za-z0-9 ._-]", "_").trim();
        if (archivo.isEmpty()) archivo = "formato";
        if (archivo.length() > 120) archivo = archivo.substring(0, 120);
        if (!archivo.toLowerCase().endsWith(".pdf")) archivo += ".pdf";

        String asunto = limitar(valor(datos, "asunto"), 200);
        if (asunto.isBlank()) asunto = "Formato DOZZER";
        String mensaje = limitar(valor(datos, "mensaje"), 3000);

        try {
            JavaMailSender sender = mailSender.getObject();
            MimeMessage correo = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(correo, true, "UTF-8");
            String remitente = mailRemitente != null && !mailRemitente.isBlank() ? mailRemitente : mailUsuario;
            if (remitente != null && !remitente.isBlank()) helper.setFrom(remitente);
            helper.setTo(destinatarios.toArray(String[]::new));
            helper.setSubject(asunto);
            helper.setText((mensaje.isBlank() ? "Se adjunta el formato generado en DOZZER." : mensaje)
                    + "\n\n--\nDOZZER · Control de activos", false);
            helper.addAttachment(archivo, new ByteArrayResource(pdf), "application/pdf");
            sender.send(correo);
        } catch (MailException | jakarta.mail.MessagingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No fue posible enviar el correo. Revisa la configuración del servidor de correo.");
        }

        return Map.of("success", true, "enviados", destinatarios.size());
    }

    private static String valor(Map<String, String> datos, String clave) {
        String v = datos == null ? null : datos.get(clave);
        return v == null ? "" : v.trim();
    }

    private static String limitar(String texto, int max) {
        return texto.length() > max ? texto.substring(0, max) : texto;
    }
}
