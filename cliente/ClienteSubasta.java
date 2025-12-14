package cliente;

import java.io.*;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Cliente de Subasta - Interfaz de usuario
 * Permite participar en subastas con múltiples ofertas
 */
public class ClienteSubasta {
    private static final int INTERVALO_OFERTAS = 8; // 8 segundos entre ofertas
    private static volatile boolean puedeOfertar = true;
    private static Timer cronometro;

    public static void main(String[] args) {
        InputStreamReader entrada = new InputStreamReader(System.in);
        BufferedReader lector = new BufferedReader(entrada);
        ClienteSubastaHelper helper = null;

        try {
            mostrarBanner();

            // Solicitar servidor
            System.out.print("🌐 Host del servidor (default: localhost): ");
            String host = lector.readLine();
            if (host.length() == 0)
                host = "localhost";

            // Solicitar puerto
            System.out.print("🔌 Puerto del servidor (default: 9090): ");
            String puerto = lector.readLine();
            if (puerto.length() == 0)
                puerto = "9090";

            System.out.println("\n────────────────────────────────────────");
            System.out.println("⚡ Conectando al servidor...");
            System.out.println("────────────────────────────────────────");

            // Conectar
            helper = new ClienteSubastaHelper(host, puerto);

            // Solicitar oferta inicial
            double oferta = solicitarOferta(lector, true);
            if (oferta <= 0) {
                System.out.println("❌ La oferta debe ser un número positivo");
                return;
            }

            // Enviar oferta inicial
            ClienteSubastaHelper.InfoEstado estado = helper.enviarOferta(oferta);
            mostrarEstado(estado);

            // Loop de ofertas
            boolean continuar = true;
            while (continuar && helper.estaActiva()) {
                // Esperar 8 segundos
                puedeOfertar = false;
                iniciarCuentaRegresiva();

                // Solicitar nueva oferta
                System.out.print("\n💰 Nueva oferta (o 'x' para salir): $");
                String input = lector.readLine();

                detenerCronometro();

                // Verificar si terminó la subasta
                if (!helper.estaActiva()) {
                    System.out.println("\n⚠ ¡La subasta ha finalizado!");
                    break;
                }

                if (input.trim().equalsIgnoreCase("x")) {
                    System.out.println("👋 Saliendo de la subasta...");
                    continuar = false;
                    break;
                }

                try {
                    double nuevaOferta = Double.parseDouble(input.trim());
                    if (nuevaOferta <= 0) {
                        System.out.println("⚠ La oferta debe ser mayor que 0");
                        continue;
                    }

                    // Enviar oferta
                    estado = helper.enviarOferta(nuevaOferta);

                    if (estado.valido) {
                        mostrarEstado(estado);
                    } else {
                        System.out.println("❌ Error: " + estado.mensajeError);
                    }

                } catch (NumberFormatException e) {
                    System.out.println("⚠ Entrada inválida. Ingrese un número");
                }
            }

            // Esperar resultado final
            if (continuar) {
                String resultado = helper.esperarResultado();
                mostrarResultadoFinal(resultado);
            }

            helper.cerrar();

        } catch (Exception ex) {
            System.out.println("❌ Error en el cliente:");
            ex.printStackTrace();
        } finally {
            detenerCronometro();
            if (helper != null) {
                try {
                    helper.cerrar();
                } catch (Exception e) {}
            }
        }
    }

    private static void mostrarBanner() {
        System.out.println("╔═══════════════════════════════════════════╗");
        System.out.println("║     🏆 SISTEMA DE SUBASTA EN VIVO 🏆     ║");
        System.out.println("╚═══════════════════════════════════════════╝");
    }

    private static double solicitarOferta(BufferedReader lector, boolean esInicial)
            throws IOException {
        String msg = esInicial ?
            "💵 Ingrese su oferta inicial: $" :
            "💵 Nueva oferta: $";

        System.out.print(msg);
        String input = lector.readLine();

        try {
            return Double.parseDouble(input.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void mostrarEstado(ClienteSubastaHelper.InfoEstado estado) {
        System.out.println("\n────────────────────────────────────────");
        System.out.println("        📊 ESTADO ACTUAL");
        System.out.println("────────────────────────────────────────");

        if (!estado.valido) {
            System.out.println("❌ " + estado.mensajeError);
            return;
        }

        System.out.println("  💵 Oferta máxima: $" + estado.montoOfertaMaxima);
        System.out.println("  📍 IP líder: " + estado.ipOfertaMaxima);
        System.out.println("  ⏱  Tiempo restante: " + estado.tiempoRestante + " seg");

        if (estado.soyLider) {
            System.out.println("  🌟 ¡ESTÁS LIDERANDO LA SUBASTA!");
        } else {
            System.out.println("  📈 Debes ofrecer más de $" + estado.montoOfertaMaxima);
        }
        System.out.println("────────────────────────────────────────");
    }

    private static void mostrarResultadoFinal(String resultado) {
        System.out.println("\n╔═══════════════════════════════════════════╗");
        System.out.println("║         🏁 RESULTADO FINAL 🏁            ║");
        System.out.println("╚═══════════════════════════════════════════╝");
        System.out.println(resultado);
        System.out.println("╚═══════════════════════════════════════════╝\n");
    }

    private static void iniciarCuentaRegresiva() {
        cronometro = new Timer();
        final int[] segundos = {INTERVALO_OFERTAS};

        cronometro.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                segundos[0]--;
                if (segundos[0] > 0) {
                    System.out.print("\r⏳ Espera " + segundos[0] + " segundos... ");
                } else {
                    System.out.print("\r✅ ¡Puedes ofertar de nuevo!         \n");
                    puedeOfertar = true;
                    cronometro.cancel();
                }
            }
        }, 1000, 1000);
    }

    private static void detenerCronometro() {
        if (cronometro != null) {
            cronometro.cancel();
            cronometro = null;
        }
    }
}