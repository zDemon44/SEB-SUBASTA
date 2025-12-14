package servidor;

import java.util.*;

/**
 * Configuración del anillo de servidores
 * Define la topología y orden de los servidores
 */
public class ConfiguracionRing {
    // Lista de servidores en el anillo (ordenados por ID)
    private static final List<InfoServidor> SERVIDORES_RING = Arrays.asList(
        new InfoServidor(1, "localhost", 9090),
        new InfoServidor(2, "localhost", 9091),
        new InfoServidor(3, "localhost", 9092)
    );

    /**
     * Información de un servidor en el anillo
     */
    public static class InfoServidor {
        public final int id;
        public final String host;
        public final int puerto;
        public final int puertoClientes;

        public InfoServidor(int id, String host, int puertoBase) {
            this.id = id;
            this.host = host;
            this.puerto = puertoBase + 1000; // Puerto para comunicación entre servidores
            this.puertoClientes = puertoBase; // Puerto para clientes
        }

        @Override
        public String toString() {
            return "Servidor[ID=" + id + ", " + host + ":" + puertoClientes + "]";
        }
    }

    /**
     * Obtiene la configuración completa del anillo
     */
    public static List<InfoServidor> obtenerServidores() {
        return new ArrayList<>(SERVIDORES_RING);
    }

    /**
     * Obtiene información de un servidor por ID
     */
    public static InfoServidor obtenerServidor(int id) {
        for (InfoServidor srv : SERVIDORES_RING) {
            if (srv.id == id) {
                return srv;
            }
        }
        return null;
    }

    /**
     * Obtiene el siguiente servidor en el anillo
     */
    public static InfoServidor siguienteServidor(int idActual) {
        int indiceActual = -1;
        for (int i = 0; i < SERVIDORES_RING.size(); i++) {
            if (SERVIDORES_RING.get(i).id == idActual) {
                indiceActual = i;
                break;
            }
        }

        if (indiceActual == -1) {
            return null;
        }

        // Siguiente en el anillo (circular)
        int siguienteIndice = (indiceActual + 1) % SERVIDORES_RING.size();
        return SERVIDORES_RING.get(siguienteIndice);
    }

    /**
     * Obtiene todos los servidores excepto el actual
     */
    public static List<InfoServidor> obtenerOtrosServidores(int idActual) {
        List<InfoServidor> otros = new ArrayList<>();
        for (InfoServidor srv : SERVIDORES_RING) {
            if (srv.id != idActual) {
                otros.add(srv);
            }
        }
        return otros;
    }

    /**
     * Verifica si un ID de servidor es válido
     */
    public static boolean esIdValido(int id) {
        return obtenerServidor(id) != null;
    }

    /**
     * Obtiene el número total de servidores en el anillo
     */
    public static int numeroServidores() {
        return SERVIDORES_RING.size();
    }
}