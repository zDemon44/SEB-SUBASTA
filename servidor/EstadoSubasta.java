package servidor;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Estado compartido de la subasta
 * Permite replicación entre servidores
 */
public class EstadoSubasta {
    private volatile double ofertaMaxima = 0.0;
    private volatile String ipOfertaMaxima = "ninguno";
    private volatile long momentoInicio = 0;
    private volatile boolean subastaActiva = false;
    
    private List<InfoParticipante> participantes = new CopyOnWriteArrayList<>();
    private final Object bloqueo = new Object();

    /**
     * Información de un participante
     */
    public static class InfoParticipante {
        public final String ip;
        public double ultimaOferta;
        public long timestamp;

        public InfoParticipante(String ip, double oferta) {
            this.ip = ip;
            this.ultimaOferta = oferta;
            this.timestamp = System.currentTimeMillis();
        }

        public void actualizarOferta(double nuevaOferta) {
            this.ultimaOferta = nuevaOferta;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * Registra una nueva oferta
     */
    public boolean registrarOferta(double oferta, String ip) {
        synchronized(bloqueo) {
            // Actualizar o agregar participante
            InfoParticipante participante = buscarParticipante(ip);
            if (participante == null) {
                participante = new InfoParticipante(ip, oferta);
                participantes.add(participante);
            } else {
                participante.actualizarOferta(oferta);
            }

            // Verificar si es la máxima
            if (oferta > ofertaMaxima) {
                ofertaMaxima = oferta;
                ipOfertaMaxima = ip;
                return true;
            }
            return false;
        }
    }

    /**
     * Actualiza la oferta máxima (usado para sincronización)
     */
    public void actualizarOfertaMaxima(double oferta, String ip) {
        synchronized(bloqueo) {
            if (oferta > ofertaMaxima) {
                ofertaMaxima = oferta;
                ipOfertaMaxima = ip;
            }

            // Actualizar participante
            InfoParticipante participante = buscarParticipante(ip);
            if (participante == null) {
                participantes.add(new InfoParticipante(ip, oferta));
            } else {
                participante.actualizarOferta(oferta);
            }
        }
    }

    /**
     * Busca un participante por IP
     */
    private InfoParticipante buscarParticipante(String ip) {
        for (InfoParticipante p : participantes) {
            if (p.ip.equals(ip)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Obtiene información de la oferta máxima
     */
    public String obtenerOfertaMaxima() {
        synchronized(bloqueo) {
            if (ofertaMaxima == 0.0) {
                return "OFERTA_MAX:ninguno:0.0";
            }
            return "OFERTA_MAX:" + ipOfertaMaxima + ":" + ofertaMaxima;
        }
    }

    /**
     * Obtiene el ganador actual
     */
    public InfoParticipante obtenerGanador() {
        synchronized(bloqueo) {
            return buscarParticipante(ipOfertaMaxima);
        }
    }

    /**
     * Obtiene todos los participantes
     */
    public List<InfoParticipante> obtenerParticipantes() {
        return new ArrayList<>(participantes);
    }

    /**
     * Reinicia el estado para una nueva subasta
     */
    public void reiniciar() {
        synchronized(bloqueo) {
            ofertaMaxima = 0.0;
            ipOfertaMaxima = "ninguno";
            participantes.clear();
            momentoInicio = 0;
            subastaActiva = false;
        }
    }

    /**
     * Inicia la subasta
     */
    public void iniciar() {
        synchronized(bloqueo) {
            momentoInicio = System.currentTimeMillis();
            subastaActiva = true;
        }
    }

    /**
     * Finaliza la subasta
     */
    public void finalizar() {
        synchronized(bloqueo) {
            subastaActiva = false;
        }
    }

    /**
     * Serializa el estado completo para replicación
     */
    public String serializar() {
        synchronized(bloqueo) {
            StringBuilder sb = new StringBuilder();
            sb.append(ofertaMaxima).append("|");
            sb.append(ipOfertaMaxima).append("|");
            sb.append(momentoInicio).append("|");
            sb.append(subastaActiva).append("|");
            
            // Participantes
            for (InfoParticipante p : participantes) {
                sb.append(p.ip).append(":").append(p.ultimaOferta).append(",");
            }
            
            return sb.toString();
        }
    }

    /**
     * Deserializa el estado desde una cadena
     */
    public void deserializar(String datos) {
        synchronized(bloqueo) {
            String[] partes = datos.split("\\|");
            if (partes.length >= 4) {
                ofertaMaxima = Double.parseDouble(partes[0]);
                ipOfertaMaxima = partes[1];
                momentoInicio = Long.parseLong(partes[2]);
                subastaActiva = Boolean.parseBoolean(partes[3]);
                
                // Participantes
                if (partes.length > 4 && !partes[4].isEmpty()) {
                    participantes.clear();
                    String[] participantesData = partes[4].split(",");
                    for (String pData : participantesData) {
                        String[] pPartes = pData.split(":");
                        if (pPartes.length == 2) {
                            participantes.add(new InfoParticipante(
                                pPartes[0], 
                                Double.parseDouble(pPartes[1])
                            ));
                        }
                    }
                }
            }
        }
    }

    // Getters
    public double getOfertaMaxima() {
        return ofertaMaxima;
    }

    public String getIpOfertaMaxima() {
        return ipOfertaMaxima;
    }

    public boolean estaActiva() {
        return subastaActiva;
    }

    public long getMomentoInicio() {
        return momentoInicio;
    }

    public int numeroParticipantes() {
        return participantes.size();
    }
}