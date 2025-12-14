package servidor;

import java.net.*;
import java.io.*;

/**
 * Clase wrapper para simplificar operaciones de Socket
 * Encapsula la lectura y escritura de mensajes
 */
public class SocketWrapper extends Socket {
    private Socket conexion;
    private BufferedReader lector;
    private PrintWriter escritor;

    public SocketWrapper(String host, int puerto) 
        throws SocketException, IOException {
        conexion = new Socket(host, puerto);
        configurarStreams();
    }

    public SocketWrapper(Socket socket) throws IOException {
        this.conexion = socket;
        configurarStreams();
    }

    private void configurarStreams() throws IOException {
        InputStream streamEntrada = conexion.getInputStream();
        lector = new BufferedReader(new InputStreamReader(streamEntrada));
        
        OutputStream streamSalida = conexion.getOutputStream();
        escritor = new PrintWriter(new OutputStreamWriter(streamSalida));
    }

    public void enviar(String mensaje) throws IOException {
        escritor.println(mensaje);
        escritor.flush();
    }

    public String recibir() throws IOException {
        String mensaje = lector.readLine();
        return mensaje;
    }

    @Override
    public void close() throws IOException {
        if (lector != null) lector.close();
        if (escritor != null) escritor.close();
        if (conexion != null) conexion.close();
    }
}