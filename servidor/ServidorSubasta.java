package servidor;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Servidor de Subasta en Tiempo Real
 * Acepta múltiples participantes, gestiona ofertas y determina ganador
 * Diseñado para sesiones continuas con reinicio automático
 */
public class ServidorSubasta {
    // Estados posibles de la subasta
    private enum Estado {
        PREPARACION,    // Esperando participantes
        EN_CURSO,       // Subasta activa
        COMPLETADA      // Subasta terminada
    }

    private static final int DURACION_SUBASTA = 90000; // 90 segundos
    private static List<ManejadorCliente> participantes = new CopyOnWriteArrayList<>();
    private static volatile Estado estadoActual = Estado.PREPARACION;
    private static long momentoInicio;
    private static Timer temporizadorPrincipal;
    private static Timer temporizadorActualizaciones;

    // Oferta máxima (thread-safe)
    private static volatile double ofertaMaxima = 0.0;
    private static volatile String ipOfertaMaxima = "ninguno";
    private static final Object bloqueo = new Object();
    private static int contadorSesiones = 0;

    public static void main(String[] args) {
        int puerto = 9090; // Puerto modificado

        if (args.length == 1)
            puerto = Integer.parseInt(args[0]);

        try {
            ServerSocket socketServidor = new ServerSocket(puerto);
            System.out.println("╔════════════════════════════════════════════╗");
            System.out.println("║  SERVIDOR DE SUBASTA ACTIVO                ║");
            System.out.println("║  Puerto: " + puerto + "                              ║");
            System.out.println("╔════════════════════════════════════════════╗");

            // Ciclo infinito para múltiples sesiones
            while (true) {
                ejecutarSesionSubasta(socketServidor);
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Ejecuta una sesión completa de subasta
     */
    private static void ejecutarSesionSubasta(ServerSocket socket) {
        try {
            contadorSesiones++;
            System.out.println("\n┌───────────────────────────────────────────┐");
            System.out.println("│  SESIÓN #" + contadorSesiones + " - PREPARACIÓN             │");
            System.out.println("└───────────────────────────────────────────┘");

            estadoActual = Estado.PREPARACION;

            // Aceptar participantes
            while (estadoActual != Estado.COMPLETADA) {
                try {
                    System.out.println("[ESPERANDO] Nueva conexión...");
                    Socket socketParticipante = socket.accept();

                    String ip = socketParticipante.getInetAddress().getHostAddress();
                    System.out.println("[CONEXIÓN] Participante desde: " + ip);

                    // Si es el primer participante, iniciar subasta
                    if (estadoActual == Estado.PREPARACION && participantes.isEmpty()) {
                        iniciarSubasta();
                    }

                    // Verificar si hay tiempo disponible
                    if (estadoActual == Estado.EN_CURSO) {
                        long tiempoTranscurrido = System.currentTimeMillis() - momentoInicio;
                        if (tiempoTranscurrido >= DURACION_SUBASTA) {
                            System.out.println("[RECHAZADO] Subasta cerrada");
                            SocketWrapper temp = new SocketWrapper(socketParticipante);
                            temp.enviar("ERR:Subasta finalizada");
                            temp.close();
                            continue;
                        }
                    }

                    // Crear manejador para el participante
                    ManejadorCliente manejador = new ManejadorCliente(
                        new SocketWrapper(socketParticipante),
                        ip
                    );
                    participantes.add(manejador);

                    Thread hilo = new Thread(manejador);
                    hilo.start();

                    // Notificar inicio si la subasta ya empezó
                    if (estadoActual == Estado.EN_CURSO) {
                        manejador.notificarInicio(segundosRestantes());
                    }

                    System.out.println("[INFO] Participantes totales: " + participantes.size());

                } catch (SocketException e) {
                    if (estadoActual == Estado.COMPLETADA) {
                        System.out.println("[INFO] Cerrando sesión...");
                    }
                }
            }

            // Esperar procesamiento final
            Thread.sleep(2000);

            // Reiniciar todo
            reiniciarEstado();

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Inicia una nueva subasta
     */
    private static void iniciarSubasta() {
        estadoActual = Estado.EN_CURSO;
        momentoInicio = System.currentTimeMillis();

        System.out.println("\n╔════════════════════════════════════════════╗");
        System.out.println("║  SUBASTA #" + contadorSesiones + " INICIADA              ║");
        System.out.println("║  Duración: " + (DURACION_SUBASTA/1000) + " segundos                   ║");
        System.out.println("╚════════════════════════════════════════════╝");

        // Cancelar timers previos
        if (temporizadorPrincipal != null) {
            temporizadorPrincipal.cancel();
        }
        if (temporizadorActualizaciones != null) {
            temporizadorActualizaciones.cancel();
        }

        // Timer de finalización
        temporizadorPrincipal = new Timer();
        temporizadorPrincipal.schedule(new TimerTask() {
            @Override
            public void run() {
                terminarSubasta();
            }
        }, DURACION_SUBASTA);

        // Timer de actualizaciones cada 4 segundos
        configurarActualizacionesPeriodicas();
    }

    /**
     * Configura envío periódico de la oferta líder cada 4 segundos
     */
    private static void configurarActualizacionesPeriodicas() {
        temporizadorActualizaciones = new Timer();
        temporizadorActualizaciones.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (estadoActual != Estado.EN_CURSO) {
                    temporizadorActualizaciones.cancel();
                    return;
                }
                transmitirActualizacion();
            }
        }, 4000, 4000); // Cada 4 segundos

        System.out.println("[SYNC] Actualizaciones cada 4 segundos activadas");
    }

    /**
     * Transmite la oferta líder actual a todos los participantes
     */
    private static void transmitirActualizacion() {
        if (participantes.isEmpty()) {
            return;
        }

        String actualizacion = obtenerOfertaMaxima() + ":TIEMPO:" + segundosRestantes();
        System.out.println("[SYNC] Oferta máxima: $" + ofertaMaxima +
                         " (" + participantes.size() + " participantes)");

        for (ManejadorCliente participante : participantes) {
            try {
                participante.notificarActualizacion(actualizacion);
            } catch (Exception e) {
                System.out.println("[ERROR] Actualizando " + participante.getDireccionIP());
            }
        }
    }

    /**
     * Termina la subasta y notifica el resultado
     */
    private static void terminarSubasta() {
        estadoActual = Estado.COMPLETADA;

        System.out.println("\n╔════════════════════════════════════════════╗");
        System.out.println("║  SUBASTA #" + contadorSesiones + " FINALIZADA            ║");
        System.out.println("╚════════════════════════════════════════════╝");

        if (participantes.isEmpty()) {
            System.out.println("[INFO] Sin participantes");
            return;
        }

        // Determinar ganador
        ManejadorCliente ganador = null;
        double ofertaGanadora = -1;

        System.out.println("\n=== OFERTAS RECIBIDAS ===");
        for (ManejadorCliente participante : participantes) {
            double oferta = participante.getOfertaActual();
            String ip = participante.getDireccionIP();
            System.out.println("  → " + ip + ": $" + oferta);

            if (oferta > ofertaGanadora) {
                ofertaGanadora = oferta;
                ganador = participante;
            }
        }

        if (ganador != null) {
            System.out.println("\n★ GANADOR: " + ganador.getDireccionIP() +
                             " - Oferta: $" + ofertaGanadora + " ★");

            // Notificar a todos
            String mensaje = "RESULTADO:" + ganador.getDireccionIP() +
                           ":OFERTA:" + ofertaGanadora;
            notificarTodos(mensaje);
        }

        // Cerrar conexiones
        for (ManejadorCliente participante : participantes) {
            participante.desconectar();
        }

        System.out.println("[INFO] Todas las conexiones cerradas");
    }

    /**
     * Notifica un mensaje a todos los participantes
     */
    private static void notificarTodos(String mensaje) {
        for (ManejadorCliente participante : participantes) {
            try {
                participante.notificarResultado(mensaje);
            } catch (Exception e) {
                System.out.println("[ERROR] Notificando " + participante.getDireccionIP());
            }
        }
    }

    /**
     * Reinicia el estado para una nueva sesión
     */
    private static void reiniciarEstado() {
        System.out.println("\n[REINICIO] Preparando nueva sesión...");

        // Cancelar timers
        if (temporizadorPrincipal != null) {
            temporizadorPrincipal.cancel();
            temporizadorPrincipal = null;
        }
        if (temporizadorActualizaciones != null) {
            temporizadorActualizaciones.cancel();
            temporizadorActualizaciones = null;
        }

        // Limpiar datos
        participantes.clear();

        synchronized(bloqueo) {
            ofertaMaxima = 0.0;
            ipOfertaMaxima = "ninguno";
        }

        estadoActual = Estado.PREPARACION;

        System.out.println("[LISTO] Sistema preparado para nueva sesión\n");
    }

    /**
     * Registra una nueva oferta y actualiza el máximo si corresponde
     */
    public static boolean registrarOferta(double nuevaOferta, String ip) {
        synchronized(bloqueo) {
            if (nuevaOferta > ofertaMaxima) {
                ofertaMaxima = nuevaOferta;
                ipOfertaMaxima = ip;
                System.out.println("[NUEVO MÁXIMO] $" + nuevaOferta + " de " + ip);
                return true;
            }
            return false;
        }
    }

    /**
     * Obtiene información de la oferta máxima
     */
    public static String obtenerOfertaMaxima() {
        synchronized(bloqueo) {
            if (ofertaMaxima == 0.0) {
                return "OFERTA_MAX:ninguno:0.0";
            }
            return "OFERTA_MAX:" + ipOfertaMaxima + ":" + ofertaMaxima;
        }
    }

    /**
     * Verifica si la subasta está en curso
     */
    public static boolean subastaEnCurso() {
        return estadoActual == Estado.EN_CURSO;
    }

    /**
     * Calcula segundos restantes de la subasta
     */
    public static long segundosRestantes() {
        if (estadoActual != Estado.EN_CURSO) {
            return 0;
        }
        long transcurrido = System.currentTimeMillis() - momentoInicio;
        long restante = (DURACION_SUBASTA - transcurrido) / 1000;
        return Math.max(0, restante);
    }
}