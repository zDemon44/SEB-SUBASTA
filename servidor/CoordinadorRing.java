package servidor;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Coordinador del Algoritmo de Anillo (VERSIÓN SIMPLIFICADA)
 * Solo los servidores que REALMENTE están activos participan
 */
public class CoordinadorRing {
    private int miId;
    private volatile int idLider = -1;
    private volatile boolean soyLider = false;
    private ServerSocket socketRing;
    private Map<Integer, Socket> conexionesServidores = new ConcurrentHashMap<>();
    private Map<Integer, PrintWriter> escritoresServidores = new ConcurrentHashMap<>();
    private volatile boolean eleccionEnCurso = false;
    
    // Estado replicado
    private EstadoSubasta estadoReplicado;
    
    // Heartbeat
    private ScheduledExecutorService heartbeatExecutor;
    private static final long HEARTBEAT_INTERVAL = 3000; // 3 segundos
    private static final long TIMEOUT_LIDER = 10000; // 10 segundos
    private volatile long ultimoHeartbeat = System.currentTimeMillis();

    public CoordinadorRing(int miId, EstadoSubasta estado) {
        this.miId = miId;
        this.estadoReplicado = estado;
    }

    /**
     * Inicia el coordinador del anillo
     */
    public void iniciar() throws IOException {
        ConfiguracionRing.InfoServidor miInfo = ConfiguracionRing.obtenerServidor(miId);
        
        System.out.println("\n╔════════════════════════════════════════════╗");
        System.out.println("║  COORDINADOR RING ACTIVO                   ║");
        System.out.println("║  ID: " + miId + "                                       ║");
        System.out.println("║  Puerto Ring: " + miInfo.puerto + "                     ║");
        System.out.println("╚════════════════════════════════════════════╝");

        // Iniciar servidor para comunicación entre servidores
        socketRing = new ServerSocket(miInfo.puerto);
        
        // Thread para aceptar conexiones de otros servidores
        new Thread(this::aceptarConexionesRing).start();
        
        // Esperar un poco para que todos los servidores arranquen
        try { Thread.sleep(2000); } catch (InterruptedException e) {}
        
        // Conectar con otros servidores
        conectarConOtrosServidores();
        
        // Iniciar elección inicial
        iniciarEleccion();
        
        // Iniciar heartbeat
        iniciarHeartbeat();
        
        // Monitor de líder
        new Thread(this::monitorearLider).start();
    }

    /**
     * Acepta conexiones de otros servidores
     */
    private void aceptarConexionesRing() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Socket socket = socketRing.accept();
                new Thread(() -> manejarMensajeServidor(socket)).start();
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                System.out.println("[RING] Error aceptando conexiones: " + e.getMessage());
            }
        }
    }

    /**
     * Conecta con otros servidores en el anillo
     */
    private void conectarConOtrosServidores() {
        List<ConfiguracionRing.InfoServidor> otros = 
            ConfiguracionRing.obtenerOtrosServidores(miId);
        
        for (ConfiguracionRing.InfoServidor srv : otros) {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(srv.host, srv.puerto), 3000);
                conexionesServidores.put(srv.id, socket);
                
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                escritoresServidores.put(srv.id, writer);
                
                System.out.println("[RING] Conectado con Servidor " + srv.id);
            } catch (IOException e) {
                System.out.println("[RING] Servidor " + srv.id + " no disponible");
            }
        }
        
        System.out.println("[RING] Total servidores conectados: " + escritoresServidores.size());
    }

    /**
     * Inicia proceso de elección - VERSIÓN SIMPLE
     */
    public synchronized void iniciarEleccion() {
        if (eleccionEnCurso) {
            return;
        }
        
        eleccionEnCurso = true;
        soyLider = false;
        
        System.out.println("\n[ELECCIÓN] Iniciando elección de líder...");
        
        // Obtener todos los servidores activos (yo + conectados)
        Set<Integer> servidoresActivos = new HashSet<>();
        servidoresActivos.add(miId);
        servidoresActivos.addAll(escritoresServidores.keySet());
        
        System.out.println("[ELECCIÓN] Servidores activos: " + servidoresActivos);
        
        // El líder es el ID más alto entre los activos
        int nuevoLider = Collections.max(servidoresActivos);
        
        System.out.println("[ELECCIÓN] Finalizada. Nuevo líder: Servidor " + nuevoLider);
        
        idLider = nuevoLider;
        soyLider = (miId == nuevoLider);
        eleccionEnCurso = false;
        
        if (soyLider) {
            System.out.println("★★★ SOY EL LÍDER ★★★");
            anunciarCoordinador();
        }
        
        ultimoHeartbeat = System.currentTimeMillis();
    }

    /**
     * Anuncia que soy el coordinador
     */
    private void anunciarCoordinador() {
        String mensaje = "COORDINADOR:" + miId;
        enviarATodosLosServidores(mensaje);
    }

    /**
     * Inicia sistema de heartbeat
     */
    private void iniciarHeartbeat() {
        heartbeatExecutor = Executors.newScheduledThreadPool(1);
        
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (soyLider) {
                enviarHeartbeat();
            }
        }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    /**
     * Envía heartbeat a todos los servidores
     */
    private void enviarHeartbeat() {
        String mensaje = "HEARTBEAT:" + miId + ":" + System.currentTimeMillis();
        enviarATodosLosServidores(mensaje);
    }

    /**
     * Monitorea si el líder está vivo
     */
    private void monitorearLider() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(2000);
                
                if (!soyLider && idLider != -1) {
                    long tiempoSinHeartbeat = System.currentTimeMillis() - ultimoHeartbeat;
                    
                    if (tiempoSinHeartbeat > TIMEOUT_LIDER) {
                        System.out.println("\n[ALERTA] Líder no responde. Iniciando nueva elección...");
                        iniciarEleccion();
                    }
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * Maneja mensajes recibidos de otros servidores
     */
    private void manejarMensajeServidor(Socket socket) {
        try {
            BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream()));
            
            String mensaje;
            while ((mensaje = in.readLine()) != null) {
                procesarMensajeRing(mensaje);
            }
        } catch (IOException e) {
            // Conexión cerrada
        }
    }

    /**
     * Procesa diferentes tipos de mensajes del ring
     */
    private void procesarMensajeRing(String mensaje) {
        try {
            if (mensaje.startsWith("COORDINADOR:")) {
                int nuevoLider = Integer.parseInt(mensaje.substring(12));
                idLider = nuevoLider;
                soyLider = (miId == nuevoLider);
                ultimoHeartbeat = System.currentTimeMillis();
                System.out.println("[RING] Nuevo coordinador: Servidor " + nuevoLider);
                
            } else if (mensaje.startsWith("HEARTBEAT:")) {
                ultimoHeartbeat = System.currentTimeMillis();
                
            } else if (mensaje.startsWith("SYNC_ESTADO:")) {
                procesarSincronizacionEstado(mensaje.substring(12));
                
            } else if (mensaje.startsWith("ELECCION_REQUEST")) {
                // Otro servidor solicita elección
                iniciarEleccion();
            }
        } catch (Exception e) {
            System.out.println("[ERROR] Procesando mensaje: " + e.getMessage());
        }
    }

    /**
     * Sincroniza el estado de la subasta
     */
    public void sincronizarEstado(double oferta, String ip) {
        if (!soyLider) {
            return;
        }
        
        String mensaje = "SYNC_ESTADO:" + oferta + ":" + ip + ":" + 
                        System.currentTimeMillis();
        enviarATodosLosServidores(mensaje);
    }

    /**
     * Procesa sincronización de estado recibida
     */
    private void procesarSincronizacionEstado(String datos) {
        try {
            String[] partes = datos.split(":");
            double oferta = Double.parseDouble(partes[0]);
            String ip = partes[1];
            
            estadoReplicado.actualizarOfertaMaxima(oferta, ip);
            System.out.println("[SYNC] Estado actualizado: $" + oferta + " de " + ip);
        } catch (Exception e) {
            System.out.println("[ERROR] Procesando sincronización: " + e.getMessage());
        }
    }

    /**
     * Envía mensaje a un servidor específico
     */
    private boolean enviarMensaje(int idDestino, String mensaje) {
        PrintWriter writer = escritoresServidores.get(idDestino);
        if (writer != null && !writer.checkError()) {
            try {
                writer.println(mensaje);
                return true;
            } catch (Exception e) {
                System.out.println("[ERROR] Enviando a Servidor " + idDestino);
                escritoresServidores.remove(idDestino);
                return false;
            }
        }
        return false;
    }

    /**
     * Envía mensaje a todos los servidores
     */
    private void enviarATodosLosServidores(String mensaje) {
        for (Integer id : new ArrayList<>(escritoresServidores.keySet())) {
            enviarMensaje(id, mensaje);
        }
    }

    // Getters
    public boolean soyLider() {
        return soyLider;
    }

    public int getIdLider() {
        return idLider;
    }

    public int getMiId() {
        return miId;
    }
    
    public void detener() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
        }
        try {
            if (socketRing != null) {
                socketRing.close();
            }
            for (Socket socket : conexionesServidores.values()) {
                socket.close();
            }
        } catch (IOException e) {
            // Ignorar
        }
    }
}