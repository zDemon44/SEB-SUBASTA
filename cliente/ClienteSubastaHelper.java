package cliente;

import servidor.SocketWrapper;
import java.net.*;
import java.io.*;

/**
 * Helper del Cliente de Subasta - Lógica de comunicación
 * Maneja el protocolo de comunicación con el servidor
 */
public class ClienteSubastaHelper {
    private SocketWrapper socket;
    private InetAddress servidorHost;
    private int servidorPuerto;
    private double miUltimaOferta = 0.0;
    private Thread hiloReceptor;
    private volatile boolean escuchando = true;
    private volatile boolean subastaVigente = true;
    private volatile String ultimaSincronizacion = "";
    private volatile String ultimaConfirmacion = null;
    private final Object lockConfirmacion = new Object();

    /**
     * Constructor - Establece conexión con el servidor
     */
    public ClienteSubastaHelper(String host, String puerto)
            throws SocketException, UnknownHostException, IOException {

        this.servidorHost = InetAddress.getByName(host);
        this.servidorPuerto = Integer.parseInt(puerto);

        // Conectar
        this.socket = new SocketWrapper(host, this.servidorPuerto);
        System.out.println("\n✓ Conectado al servidor: " + host + ":" + puerto);

        // Iniciar hilo receptor
        iniciarReceptor();
    }

    /**
     * Envía una oferta y obtiene respuesta del servidor
     */
    public InfoEstado enviarOferta(double oferta)
            throws SocketException, IOException {

        miUltimaOferta = oferta;

        synchronized(lockConfirmacion) {
            ultimaConfirmacion = null;

            // Enviar oferta
            socket.enviar(String.valueOf(oferta));

            // Esperar confirmación (timeout 10 seg)
            try {
                lockConfirmacion.wait(10000);
            } catch (InterruptedException e) {
                return new InfoEstado(false, "Timeout esperando respuesta", "", 0.0, 0, false);
            }

            if (ultimaConfirmacion == null) {
                return new InfoEstado(false, "Sin respuesta del servidor", "", 0.0, 0, false);
            }

            // Procesar confirmación
            return procesarRespuesta(ultimaConfirmacion);
        }
    }

    /**
     * Espera el resultado final
     */
    public String esperarResultado() throws SocketException, IOException {
        System.out.println("\n⏳ Aguardando resultado final...");

        // Esperar a que el hilo receptor termine
        try {
            if (hiloReceptor != null) {
                hiloReceptor.join(30000);
            }
        } catch (InterruptedException e) {
            System.out.println("Espera interrumpida: " + e.getMessage());
        }

        // Si ya recibimos resultado, usarlo
        if (ultimaSincronizacion != null && ultimaSincronizacion.startsWith("RESULTADO:")) {
            return formatearResultado(ultimaSincronizacion);
        }

        // Si no, leer directamente
        String resultado = socket.recibir();
        return formatearResultado(resultado);
    }

    /**
     * Procesa la respuesta del servidor
     */
    private InfoEstado procesarRespuesta(String respuesta) {
        try {
            if (respuesta.startsWith("ERR")) {
                return new InfoEstado(false, respuesta, "", 0.0, 0, false);
            }

            // Remover prefijo CONF: si existe
            if (respuesta.startsWith("CONF:")) {
                respuesta = respuesta.substring(5);
            }

            // Formato: OFERTA_MAX:ip:monto:TIEMPO:segundos:ESTADO:estado
            String[] partes = respuesta.split(":");

            String ipMax = partes[1];
            double montoMax = Double.parseDouble(partes[2]);
            long tiempoRestante = Long.parseLong(partes[4]);
            boolean soyLider = partes[6].equals("LIDER");

            return new InfoEstado(true, "", ipMax, montoMax,
                                tiempoRestante, soyLider);

        } catch (Exception e) {
            return new InfoEstado(false, "Error procesando: " + respuesta,
                                "", 0.0, 0, false);
        }
    }

    /**
     * Formatea el resultado final
     */
    private String formatearResultado(String resultado) {
        try {
            // Formato: "RESULTADO:IP:OFERTA:cantidad"
            String[] partes = resultado.split(":");
            if (partes.length >= 4) {
                String ipGanador = partes[1];
                double montoGanador = Double.parseDouble(partes[3]);

                StringBuilder sb = new StringBuilder();
                sb.append("\n  🏆 Ganador: ").append(ipGanador).append("\n");
                sb.append("  💰 Oferta ganadora: $").append(montoGanador).append("\n");
                sb.append("  📊 Tu última oferta: $").append(miUltimaOferta).append("\n\n");

                // Verificar si ganó
                if (miUltimaOferta == montoGanador) {
                    sb.append("  ✨ ¡FELICITACIONES! ¡GANASTE LA SUBASTA! ✨");
                } else {
                    double diferencia = montoGanador - miUltimaOferta;
                    sb.append("  ❌ No ganaste esta vez.");
                    sb.append("\n  Te faltaron $").append(String.format("%.2f", diferencia));
                }

                return sb.toString();
            }
        } catch (Exception e) {
            System.out.println("Error procesando resultado: " + e.getMessage());
        }

        return "Resultado: " + resultado;
    }

    /**
     * Verifica si la subasta está activa
     */
    public boolean estaActiva() {
        return subastaVigente;
    }

    /**
     * Cierra la conexión
     */
    public void cerrar() throws SocketException, IOException {
        escuchando = false;
        subastaVigente = false;
        if (hiloReceptor != null) {
            hiloReceptor.interrupt();
        }
        socket.close();
        System.out.println("\n✓ Conexión cerrada");
    }

    /**
     * Inicia hilo para recibir mensajes del servidor
     */
    private void iniciarReceptor() {
        hiloReceptor = new Thread(() -> {
            try {
                while (escuchando) {
                    String mensaje = socket.recibir();

                    if (mensaje == null) {
                        System.out.println("\n[INFO] Servidor cerró la conexión");
                        break;
                    }

                    // Procesar diferentes tipos de mensajes
                    if (mensaje.startsWith("INICIO:")) {
                        procesarInicio(mensaje);
                    } else if (mensaje.startsWith("SYNC:")) {
                        procesarSincronizacion(mensaje.substring(5));
                    } else if (mensaje.startsWith("RESULTADO:")) {
                        ultimaSincronizacion = mensaje;
                        subastaVigente = false;
                        escuchando = false;
                        break;
                    } else if (mensaje.startsWith("CONF:") || mensaje.startsWith("ERR:")) {
                        synchronized(lockConfirmacion) {
                            ultimaConfirmacion = mensaje;
                            lockConfirmacion.notifyAll();
                        }
                    }
                }
            } catch (IOException e) {
                if (escuchando) {
                    System.out.println("\nError en receptor: " + e.getMessage());
                }
            }
        });
        hiloReceptor.setDaemon(false);
        hiloReceptor.start();
    }

    /**
     * Procesa mensaje de inicio
     */
    private void procesarInicio(String mensaje) {
        try {
            // Formato: INICIO:DURACION:segundos
            String[] partes = mensaje.split(":");
            if (partes.length >= 3) {
                long duracion = Long.parseLong(partes[2]);

                System.out.println("\n════════════════════════════════════════");
                System.out.println("    🚀 ¡SUBASTA INICIADA! 🚀");
                System.out.println("════════════════════════════════════════");
                System.out.println("  ⏱  Duración: " + duracion + " segundos");
                System.out.println("  📝 Intervalo entre ofertas: 8 segundos");
                System.out.println("════════════════════════════════════════\n");
            }
        } catch (Exception e) {
            System.out.println("Error procesando inicio: " + e.getMessage());
        }
    }

    /**
     * Procesa sincronización periódica
     */
    private void procesarSincronizacion(String sync) {
        try {
            // Formato: OFERTA_MAX:ip:monto:TIEMPO:segundos
            String[] partes = sync.split(":");
            if (partes.length >= 5) {
                String ipLider = partes[1];
                double montoLider = Double.parseDouble(partes[2]);
                long tiempoRestante = Long.parseLong(partes[4]);

                System.out.println("\n[📡 ACTUALIZACIÓN]");
                System.out.println("  💵 Oferta líder: $" + montoLider + " (IP: " + ipLider + ")");
                System.out.println("  ⏱  Tiempo restante: " + tiempoRestante + " seg");
                System.out.println("────────────────────────────────────────");
            }
        } catch (Exception e) {
            System.out.println("Error en sincronización: " + e.getMessage());
        }
    }

    /**
     * Clase para representar el estado de la subasta
     */
    public static class InfoEstado {
        public final boolean valido;
        public final String mensajeError;
        public final String ipOfertaMaxima;
        public final double montoOfertaMaxima;
        public final long tiempoRestante;
        public final boolean soyLider;

        public InfoEstado(boolean valido, String error, String ip, double monto,
                         long tiempo, boolean lider) {
            this.valido = valido;
            this.mensajeError = error;
            this.ipOfertaMaxima = ip;
            this.montoOfertaMaxima = monto;
            this.tiempoRestante = tiempo;
            this.soyLider = lider;
        }
    }
}