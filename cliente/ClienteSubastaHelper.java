package cliente;

import servidor.SocketWrapper;
import java.net.*;
import java.io.*;
import java.util.*;

/**
 * Helper del Cliente con capacidad de Failover
 * Se reconecta automáticamente si el servidor falla
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

    // *** LISTA DE SERVIDORES PARA FAILOVER ***
    private List<ServidorInfo> servidoresDisponibles;
    private int indiceServidorActual = 0;
    private static final int MAX_REINTENTOS = 3;
    private static final int TIMEOUT_RECONEXION = 5000; // 5 segundos

    private static class ServidorInfo {
        String host;
        int puerto;

        ServidorInfo(String host, int puerto) {
            this.host = host;
            this.puerto = puerto;
        }
    }

    /**
     * Constructor - Establece conexión con el servidor
     */
    public ClienteSubastaHelper(String host, String puerto)
            throws SocketException, UnknownHostException, IOException {

        this.servidorHost = InetAddress.getByName(host);
        this.servidorPuerto = Integer.parseInt(puerto);

        // Inicializar lista de servidores disponibles
        inicializarServidores(host);

        // Conectar
        conectarConFailover();

        // Iniciar hilo receptor
        iniciarReceptor();
    }

    /**
     * Inicializa la lista de servidores disponibles
     */
    private void inicializarServidores(String hostPrincipal) {
        servidoresDisponibles = new ArrayList<>();
        
        // Agregar servidores configurados
        servidoresDisponibles.add(new ServidorInfo("localhost", 9090));
        servidoresDisponibles.add(new ServidorInfo("localhost", 9091));
        servidoresDisponibles.add(new ServidorInfo("localhost", 9092));

        // Buscar el servidor al que nos conectamos primero
        for (int i = 0; i < servidoresDisponibles.size(); i++) {
            if (servidoresDisponibles.get(i).puerto == servidorPuerto) {
                indiceServidorActual = i;
                break;
            }
        }
    }

    /**
     * Intenta conectar con failover automático
     */
    private boolean conectarConFailover() {
        int intentos = 0;
        int servidorInicio = indiceServidorActual;

        while (intentos < servidoresDisponibles.size()) {
            ServidorInfo servidor = servidoresDisponibles.get(indiceServidorActual);
            
            try {
                System.out.println("\n[FAILOVER] Intentando conectar a " + 
                                 servidor.host + ":" + servidor.puerto);
                
                this.socket = new SocketWrapper(servidor.host, servidor.puerto);
                this.servidorHost = InetAddress.getByName(servidor.host);
                this.servidorPuerto = servidor.puerto;
                
                System.out.println("✓ Conectado al servidor: " + servidor.host + 
                                 ":" + servidor.puerto);
                return true;

            } catch (IOException e) {
                System.out.println("✗ Servidor no disponible: " + servidor.host + 
                                 ":" + servidor.puerto);
                
                // Probar siguiente servidor
                indiceServidorActual = (indiceServidorActual + 1) % 
                                      servidoresDisponibles.size();
                intentos++;

                // Si volvimos al servidor inicial, esperar antes de reintentar
                if (indiceServidorActual == servidorInicio) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {}
                }
            }
        }

        System.out.println("❌ No se pudo conectar a ningún servidor");
        return false;
    }

    /**
     * Reconecta automáticamente si se pierde la conexión
     */
    private boolean reconectar() {
        System.out.println("\n⚠ Conexión perdida. Intentando reconectar...");
        
        // Cerrar socket antiguo
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {}

        // Intentar conectar con otro servidor
        int servidorOriginal = indiceServidorActual;
        indiceServidorActual = (indiceServidorActual + 1) % 
                              servidoresDisponibles.size();

        for (int i = 0; i < MAX_REINTENTOS; i++) {
            if (conectarConFailover()) {
                System.out.println("✓ Reconexión exitosa!");
                
                // Reenviar última oferta si existe
                if (miUltimaOferta > 0) {
                    try {
                        System.out.println("[INFO] Reenviando última oferta: $" + 
                                         miUltimaOferta);
                        socket.enviar(String.valueOf(miUltimaOferta));
                    } catch (IOException e) {
                        System.out.println("⚠ No se pudo reenviar oferta");
                    }
                }
                
                return true;
            }

            try {
                Thread.sleep(TIMEOUT_RECONEXION);
            } catch (InterruptedException e) {}
        }

        System.out.println("❌ No se pudo reconectar después de " + 
                         MAX_REINTENTOS + " intentos");
        return false;
    }

    /**
     * Envía una oferta y obtiene respuesta del servidor
     */
    public InfoEstado enviarOferta(double oferta)
            throws SocketException, IOException {

        miUltimaOferta = oferta;

        synchronized(lockConfirmacion) {
            ultimaConfirmacion = null;

            try {
                // Enviar oferta
                socket.enviar(String.valueOf(oferta));

                // Esperar confirmación (timeout 10 seg)
                try {
                    lockConfirmacion.wait(10000);
                } catch (InterruptedException e) {
                    return new InfoEstado(false, "Timeout esperando respuesta", 
                                        "", 0.0, 0, false);
                }

                if (ultimaConfirmacion == null) {
                    // Intentar reconectar
                    if (reconectar()) {
                        // Reenviar oferta
                        socket.enviar(String.valueOf(oferta));
                        try {
                            lockConfirmacion.wait(10000);
                        } catch (InterruptedException e) {}
                    }

                    if (ultimaConfirmacion == null) {
                        return new InfoEstado(false, "Sin respuesta del servidor", 
                                            "", 0.0, 0, false);
                    }
                }

                // Procesar confirmación
                return procesarRespuesta(ultimaConfirmacion);

            } catch (IOException e) {
                // Intentar reconectar
                if (reconectar()) {
                    return enviarOferta(oferta); // Reintentar
                }
                throw e;
            }
        }
    }

    /**
     * Espera el resultado final
     */
    public String esperarResultado() throws SocketException, IOException {
        System.out.println("\n⏳ Aguardando resultado final...");

        try {
            if (hiloReceptor != null) {
                hiloReceptor.join(30000);
            }
        } catch (InterruptedException e) {
            System.out.println("Espera interrumpida: " + e.getMessage());
        }

        if (ultimaSincronizacion != null && 
            ultimaSincronizacion.startsWith("RESULTADO:")) {
            return formatearResultado(ultimaSincronizacion);
        }

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

            if (respuesta.startsWith("CONF:")) {
                respuesta = respuesta.substring(5);
            }

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
            String[] partes = resultado.split(":");
            if (partes.length >= 4) {
                String ipGanador = partes[1];
                double montoGanador = Double.parseDouble(partes[3]);

                StringBuilder sb = new StringBuilder();
                sb.append("\n  🏆 Ganador: ").append(ipGanador).append("\n");
                sb.append("  💰 Oferta ganadora: $").append(montoGanador).append("\n");
                sb.append("  📊 Tu última oferta: $").append(miUltimaOferta).append("\n\n");

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
                        
                        // Intentar reconectar
                        if (subastaVigente && reconectar()) {
                            continue;
                        }
                        break;
                    }

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
                if (escuchando && subastaVigente) {
                    System.out.println("\n⚠ Error de conexión");
                    reconectar();
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