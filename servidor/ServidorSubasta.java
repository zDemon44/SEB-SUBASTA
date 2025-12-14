package servidor;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Servidor de Subasta con Tolerancia a Fallos
 * Implementa algoritmo de anillo para elección de líder
 */
public class ServidorSubasta {
    private enum Estado {
        PREPARACION,
        EN_CURSO,
        COMPLETADA
    }

    private static final int DURACION_SUBASTA = 90000; // 90 segundos
    private static List<ManejadorCliente> participantes = new CopyOnWriteArrayList<>();
    private static volatile Estado estadoActual = Estado.PREPARACION;
    private static long momentoInicio;
    private static Timer temporizadorPrincipal;
    private static Timer temporizadorActualizaciones;
    private static int contadorSesiones = 0;

    // *** NUEVAS VARIABLES PARA RING ***
    private static CoordinadorRing coordinadorRing;
    private static EstadoSubasta estadoCompartido;
    private static int miIdServidor;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: java servidor.ServidorSubasta <ID_SERVIDOR>");
            System.out.println("IDs válidos: 1, 2, 3");
            return;
        }

        miIdServidor = Integer.parseInt(args[0]);

        if (!ConfiguracionRing.esIdValido(miIdServidor)) {
            System.out.println("❌ ID de servidor inválido: " + miIdServidor);
            return;
        }

        ConfiguracionRing.InfoServidor miInfo = 
            ConfiguracionRing.obtenerServidor(miIdServidor);

        try {
            // Inicializar estado compartido
            estadoCompartido = new EstadoSubasta();

            // Iniciar coordinador de anillo
            coordinadorRing = new CoordinadorRing(miIdServidor, estadoCompartido);
            coordinadorRing.iniciar();

            // Iniciar servidor de clientes
            ServerSocket socketServidor = new ServerSocket(miInfo.puertoClientes);
            
            System.out.println("\n╔════════════════════════════════════════════╗");
            System.out.println("║  SERVIDOR DE SUBASTA ACTIVO                ║");
            System.out.println("║  ID: " + miIdServidor + "                                      ║");
            System.out.println("║  Puerto Clientes: " + miInfo.puertoClientes + "                    ║");
            System.out.println("║  Puerto Ring: " + miInfo.puerto + "                        ║");
            System.out.println("╚════════════════════════════════════════════╝");

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

                    // *** SOLO EL LÍDER INICIA LA SUBASTA ***
                    if (estadoActual == Estado.PREPARACION && 
                        participantes.isEmpty() && 
                        coordinadorRing.soyLider()) {
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

            Thread.sleep(2000);
            reiniciarEstado();

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Inicia una nueva subasta (solo líder)
     */
    private static void iniciarSubasta() {
        if (!coordinadorRing.soyLider()) {
            System.out.println("[AVISO] No soy líder, no puedo iniciar subasta");
            return;
        }

        estadoActual = Estado.EN_CURSO;
        momentoInicio = System.currentTimeMillis();
        estadoCompartido.iniciar();

        System.out.println("\n╔════════════════════════════════════════════╗");
        System.out.println("║  SUBASTA #" + contadorSesiones + " INICIADA [LÍDER]       ║");
        System.out.println("║  Duración: " + (DURACION_SUBASTA/1000) + " segundos                   ║");
        System.out.println("╚════════════════════════════════════════════╝");

        if (temporizadorPrincipal != null) {
            temporizadorPrincipal.cancel();
        }
        if (temporizadorActualizaciones != null) {
            temporizadorActualizaciones.cancel();
        }

        temporizadorPrincipal = new Timer();
        temporizadorPrincipal.schedule(new TimerTask() {
            @Override
            public void run() {
                terminarSubasta();
            }
        }, DURACION_SUBASTA);

        configurarActualizacionesPeriodicas();
    }

    /**
     * Configura actualizaciones periódicas
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
        }, 4000, 4000);

        System.out.println("[SYNC] Actualizaciones cada 4 segundos activadas");
    }

    /**
     * Transmite actualización a todos los participantes
     */
    private static void transmitirActualizacion() {
        if (participantes.isEmpty()) {
            return;
        }

        String actualizacion = estadoCompartido.obtenerOfertaMaxima() + 
                              ":TIEMPO:" + segundosRestantes();
        
        System.out.println("[SYNC] Oferta máxima: $" + estadoCompartido.getOfertaMaxima() +
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
     * Termina la subasta
     */
    private static void terminarSubasta() {
        estadoActual = Estado.COMPLETADA;
        estadoCompartido.finalizar();

        System.out.println("\n╔════════════════════════════════════════════╗");
        System.out.println("║  SUBASTA #" + contadorSesiones + " FINALIZADA            ║");
        System.out.println("╚════════════════════════════════════════════╝");

        if (participantes.isEmpty()) {
            System.out.println("[INFO] Sin participantes");
            return;
        }

        // Usar estado compartido para determinar ganador
        EstadoSubasta.InfoParticipante ganador = estadoCompartido.obtenerGanador();
        
        System.out.println("\n=== OFERTAS RECIBIDAS ===");
        for (EstadoSubasta.InfoParticipante p : estadoCompartido.obtenerParticipantes()) {
            System.out.println("  → " + p.ip + ": $" + p.ultimaOferta);
        }

        if (ganador != null && ganador.ultimaOferta > 0) {
            System.out.println("\n★ GANADOR: " + ganador.ip +
                             " - Oferta: $" + ganador.ultimaOferta + " ★");

            String mensaje = "RESULTADO:" + ganador.ip +
                           ":OFERTA:" + ganador.ultimaOferta;
            notificarTodos(mensaje);
        }

        for (ManejadorCliente participante : participantes) {
            participante.desconectar();
        }

        System.out.println("[INFO] Todas las conexiones cerradas");
    }

    /**
     * Notifica mensaje a todos los participantes
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
     * Reinicia el estado
     */
    private static void reiniciarEstado() {
        System.out.println("\n[REINICIO] Preparando nueva sesión...");

        if (temporizadorPrincipal != null) {
            temporizadorPrincipal.cancel();
            temporizadorPrincipal = null;
        }
        if (temporizadorActualizaciones != null) {
            temporizadorActualizaciones.cancel();
            temporizadorActualizaciones = null;
        }

        participantes.clear();
        estadoCompartido.reiniciar();
        estadoActual = Estado.PREPARACION;

        System.out.println("[LISTO] Sistema preparado para nueva sesión\n");
    }

    /**
     * Registra una nueva oferta (con sincronización Ring)
     */
    public static boolean registrarOferta(double nuevaOferta, String ip) {
        boolean esMaxima = estadoCompartido.registrarOferta(nuevaOferta, ip);
        
        if (esMaxima) {
            System.out.println("[NUEVO MÁXIMO] $" + nuevaOferta + " de " + ip);
            
            // *** SINCRONIZAR CON OTROS SERVIDORES ***
            if (coordinadorRing.soyLider()) {
                coordinadorRing.sincronizarEstado(nuevaOferta, ip);
            }
        }
        
        return esMaxima;
    }

    /**
     * Obtiene información de la oferta máxima
     */
    public static String obtenerOfertaMaxima() {
        return estadoCompartido.obtenerOfertaMaxima();
    }

    /**
     * Verifica si la subasta está en curso
     */
    public static boolean subastaEnCurso() {
        return estadoActual == Estado.EN_CURSO;
    }

    /**
     * Calcula segundos restantes
     */
    public static long segundosRestantes() {
        if (estadoActual != Estado.EN_CURSO) {
            return 0;
        }
        long transcurrido = System.currentTimeMillis() - momentoInicio;
        long restante = (DURACION_SUBASTA - transcurrido) / 1000;
        return Math.max(0, restante);
    }

    // Getters para el Ring
    public static CoordinadorRing getCoordinador() {
        return coordinadorRing;
    }

    public static EstadoSubasta getEstadoCompartido() {
        return estadoCompartido;
    }
}