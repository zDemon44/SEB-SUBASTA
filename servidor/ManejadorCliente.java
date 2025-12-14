package servidor;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * Thread que gestiona la comunicación con un participante de la subasta
 * Permite recibir múltiples ofertas hasta que termine el tiempo
 */
public class ManejadorCliente implements Runnable {
    private SocketWrapper socket;
    private String direccionIP;
    private double ofertaActual;
    private volatile boolean notificado = false;
    private CountDownLatch esperaFinal = new CountDownLatch(1);

    public ManejadorCliente(SocketWrapper socket, String ip) {
        this.socket = socket;
        this.direccionIP = ip;
        this.ofertaActual = 0.0;
    }

    @Override
    public void run() {
        try {
            // Ciclo para recibir múltiples ofertas del participante
            while (ServidorSubasta.subastaEnCurso()) {
                String dato = socket.recibir();

                if (dato == null) {
                    System.out.println("Participante " + direccionIP + " desconectado");
                    break;
                }

                System.out.println("Recibido de " + direccionIP + ": " + dato);

                // Comando de salida
                if (dato.trim().equalsIgnoreCase("SALIR")) {
                    System.out.println("Participante " + direccionIP + " abandonó");
                    break;
                }

                try {
                    double nuevaOferta = Double.parseDouble(dato.trim());

                    if (nuevaOferta <= 0) {
                        socket.enviar("ERR:Oferta debe ser positiva");
                        continue;
                    }

                    // Guardar oferta del cliente
                    ofertaActual = nuevaOferta;
                    System.out.println("Participante " + direccionIP + " ofrece: $" + ofertaActual);

                    // Verificar si es la oferta más alta
                    boolean esGanador = ServidorSubasta.registrarOferta(
                        nuevaOferta, direccionIP);

                    // Enviar confirmación con estado actual
                    String confirmacion = "CONF:" + ServidorSubasta.obtenerOfertaMaxima() +
                                        ":TIEMPO:" + ServidorSubasta.segundosRestantes() +
                                        ":ESTADO:" + (esGanador ? "LIDER" : "SIGUIENDO");

                    socket.enviar(confirmacion);

                } catch (NumberFormatException e) {
                    System.out.println("Oferta inválida de " + direccionIP);
                    socket.enviar("ERR:Formato de oferta incorrecto");
                }
            }

            // Aguardar resultado final
            System.out.println("Participante " + direccionIP + " aguardando resultado...");
            esperaFinal.await();

        } catch (InterruptedException e) {
            System.out.println("Participante " + direccionIP + " interrumpido");
        } catch (IOException e) {
            System.out.println("Error I/O con " + direccionIP + ": " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Error con " + direccionIP + ": " + e.getMessage());
        }
    }

    /**
     * Envía el resultado final al participante
     */
    public void notificarResultado(String mensaje) {
        try {
            if (!notificado) {
                socket.enviar(mensaje);
                notificado = true;
                esperaFinal.countDown();
                System.out.println("Resultado enviado a " + direccionIP);
            }
        } catch (IOException e) {
            System.out.println("Error enviando resultado a " + direccionIP);
        }
    }

    /**
     * Envía actualización periódica de la oferta líder
     */
    public void notificarActualizacion(String mensaje) {
        try {
            socket.enviar("SYNC:" + mensaje);
        } catch (IOException e) {
            System.out.println("Error enviando actualización a " + direccionIP);
        }
    }

    /**
     * Notifica que la subasta comenzó
     */
    public void notificarInicio(long segundos) {
        try {
            socket.enviar("INICIO:DURACION:" + segundos);
            System.out.println("Participante " + direccionIP + " notificado del inicio");
        } catch (IOException e) {
            System.out.println("Error notificando inicio a " + direccionIP);
        }
    }

    /**
     * Cierra la conexión
     */
    public void desconectar() {
        try {
            if (!notificado) {
                esperaFinal.countDown();
            }
            socket.close();
            System.out.println("Conexión cerrada: " + direccionIP);
        } catch (IOException e) {
            System.out.println("Error cerrando conexión: " + direccionIP);
        }
    }

    // Getters
    public double getOfertaActual() {
        return ofertaActual;
    }

    public String getDireccionIP() {
        return direccionIP;
    }
}