import java.io.*;
import java.net.*;
import java.util.*;
import java.text.SimpleDateFormat;

/**
 * A java web server implementing GET, HEAD and TRACE HTTP requests.
 * @author James Benson
 */
public class WebServer implements Runnable {
  public static final boolean LOGGING = false;

  public static final String PUBLIC_DIR = "www";
  public static final String[] DEFAULT_FILES = new String[] { "index.html", "index.htm" };
  public static final String SERVER_LOG = "access-log.txt";

  public static final String[] VALID_METHODS = new String[] { "OPTIONS", "GET", "HEAD", "POST", "PUT", "DELETE", "TRACE", "CONNECT" };

  // Connection
  private Socket sock = null;
  private InputStream fromClient = null;
  private OutputStream toClient = null;

  private Calendar timestamp = null;
  private String date = null;
  private String[] headerLines = null;
  private String method = null;
  private String resource = null;
  private String version  = null;
  private File resourceFile = null;
  private SimpleDateFormat dateOptionFormat = null;

  /**
   * Creates a new connection for a single client this will run in its own thread.
   * @param connectedSock The socket this client is connected to.
   */
  public WebServer(Socket connectedSock) {
    sock = connectedSock;
  }

  /**
   * The main server program, can be started in the terminal with a port number 
   * to run the server on.
   * @param args Command line arguments, only accepts one number between (0-65535) 
   * for setting the port number of the server.
   */
  public static void main(String args[]) throws Exception {
  	if (args.length != 1) {
  	  System.out.println("Usage: WebServer port");
  	  System.exit(1);
  	}
  	// Start a server on a given port number.
  	int port = -1;
  	ServerSocket serverSock = null;
  	try {
  		port = Integer.parseInt(args[0]);
  		serverSock = new ServerSocket(port);
  	} catch (NumberFormatException nf) {
  		System.out.println("Port number given is not a number.");
  		System.exit(1);
  	} catch (IllegalArgumentException ia) {
  		System.out.println("Port number must be between 0 65535.");
  		System.exit(1);
  	} catch (IOException io) {
  		System.out.println("Port number is already in use.");
  		System.exit(1);
  	}

  	logln("Server started on port " + port);

  	while(true) {
  		// Wait for a connection to be made.
  		Socket conn = serverSock.accept();
  		Thread worker = new Thread(new WebServer(conn));
  		worker.start();
  	}
  }

  /**
   * New thread for each client/connection. Reads from the socket, interprets the http request, finds the requested 
   * file and sends a response back down the socket.
   */
  public void run() {
    try {
      try {
        WebServer.logln("------ Received New Request ------");
        timestamp = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        dateOptionFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss");
        date = dateOptionFormat.format(timestamp.getTime()) + " GMT";

        WebServer.logln("Timestamp: " + date);

        readHeader();
        if (!validOptionLines()) {
          throw new BadRequestException();
        }

        WebServer.logln("\nMethod: " + method
               + "\nResource: " + resource
               + "\nVersion: " + version);
        
        resourceFile = new File(WebServer.PUBLIC_DIR + resource);
        
        switch (method) {
          case "GET" :
            get();
            break;
          case "HEAD" :
            head();
            break;
          case "TRACE" :
            trace();
            break;
          default :
            throw new NotImplementedException();
        }
      } catch (BadRequestException br) {
        String errorResource = "<h1>Bad Request</h1>\r\n";
        respondWithHeadCheck("HTTP/1.1 400 Bad Request\r\n"
            + "Date: " + date + "\r\n"
            + "Connection: close\r\n"
            + "Server: bws\r\n"
            + "Content-Length: " + errorResource.length() + "\r\n"
            + "Content-Type: text/html\r\n"
            + "\r\n",
            new ByteArrayInputStream(errorResource.getBytes()));
        } catch (NotFoundException nf) {
          String errorResource = "<h1>Page Not Found</h1>\r\n";
        respondWithHeadCheck("HTTP/1.1 404 Not Found\r\n"
            + "Date: " + date + "\r\n"
            + "Connection: close\r\n"
            + "Server: bws\r\n"
            + "Content-Length: " + errorResource.length() + "\r\n"
            + "Content-Type: text/html\r\n"
            + "\r\n",
            new ByteArrayInputStream(errorResource.getBytes()));
      } catch (NotImplementedException ni) {
        String errorResource = "<h1>Not Implemented</h1>\r\n";
        respondWithHeadCheck("HTTP/1.1 501 Not Implemented\r\n"
            + "Date: " + date + "\r\n"
            + "Connection: close\r\n"
            + "Server: bws\r\n"
            + "Content-Length: " + errorResource.length() + "\r\n"
            + "Content-Type: text/html\r\n"
            + "\r\n",
            new ByteArrayInputStream(errorResource.getBytes()));
      } finally {
        close();
      }
    } catch (IOException io) {
      WebServer.logln("IO ERROR: " + io.getMessage());
    }
  }

  /**
   * Reads the header of a request upto the blank line, puts it in the headerLines array and
   * checks whether it is a valid request.
   */
  private void readHeader() throws BadRequestException, IOException {
    fromClient = sock.getInputStream();

    String[] headerBuffer = new String[64];
    String line = null;
    int lineCount = 0;
    while (true) {
      line = readLine(fromClient);
      if (line.length() == 0) break;
      headerBuffer[lineCount] = line;
      WebServer.logln("(" + lineCount + ") " + line);
      lineCount = lineCount + 1;
      if (lineCount == 64) break;
    }

    // Store the header lines from the buffer into the headerLines array
    headerLines = new String[lineCount];
    for (int i = 0; i < lineCount; i++) {
      headerLines[i] = headerBuffer[i];
    }

    // Check that the request is valid.
    if (headerLines.length == 0) {
      throw new BadRequestException();
    }
    String[] request = headerLines[0].split(" ");
    if (request.length != 3 || !WebServer.validMethod(request[0])) {
      throw new BadRequestException();
    }
    method = request[0];
    resource = request[1];
    if (!resource.startsWith("/") || !request[2].startsWith("HTTP/")) {
      throw new BadRequestException();
    }
    version = request[2].substring(5);
  }

  /**
   * Reads a line at a time from the given input stream.
   * @param in The input stream to read from.
   * @return A string containing a line from the input stream.
   */
  private static String readLine(InputStream in) throws IOException {
    final int BSIZE = 32768;
    byte[] buffer = new byte[BSIZE];
    int byteCount = 0;
    int zeroCount = 0;
    // Read one byte at a time looking for the end of line markers.
    while (true) {
      int rc = in.read(buffer, byteCount, 1);

      if (buffer[byteCount] == '\n') break;
      if (buffer[byteCount] != '\r') {
        // When testing in a browser my read line and previously Scanner read line would freeze and crash after sending the response 
        // due to a new connection by the browser sending an 'empty' stream of zeros. So the readline's buffer would overflow before
        // reading a new line. I guess some browsers try to force TCP connections to stay open. This will prevent these connections.
        // NB when running concurrently this may not be/less of an issue as the worker threads will just die.
        if (buffer[byteCount] == 0) {
          if (++zeroCount > 16) {
            throw new IOException("Connection flooded with zeros.");
          }
        }

        if (++byteCount >= BSIZE) {
          throw new IOException("ReadLine buffer overflow. Increase the size of the buffer.");
        }
      }
    }
    return new String(buffer, 0, byteCount);
  }

  /**
   * Checks whether the option lines are valid for the version of HTTP.
   * @return True if the options are valid, false if not.
   */
  private boolean validOptionLines() {
    if (version.equals("1.1")) {
      // version 1.1 requires there to be a Host option.
      return getOption("host") != null;
    }
    return true;
  }

  /**
   * Searches the option lines for a given option.
   * @param option The option name, which is case insensitive, to look for. ie 'host', 'connection'
   * @return The value of the option or null if the option was not found.
   */
  private String getOption(String option) {
    for (String line : headerLines) {
      if (line.contains(":")) {
        String[] l = line.split(":");
        if (l[0].toLowerCase().equals(option.toLowerCase())) {
          return l[1].trim();
        }
      }
    }
    return null;
  }

  /**
   * Responds to a HTTP GET request. Builds the response header for the given file and 
   * sends the file to the client if it exists.
   */
  private void get() throws NotFoundException, IOException {
    try {
      respond(buildGetHeader(), new FileInputStream(resourceFile));
    } catch (FileNotFoundException e) {
      // new FileInputStream should never fail as buildGetHeader deals with not found files.
      throw new NotFoundException();
    }
  }

  /**
   * Responds to a HTTP HEAD request. Only sends the HTTP headers without the message body.
   */
  private void head() throws NotFoundException, IOException {
    respond(buildGetHeader(), null);
  }

  /**
   * Responds to a HTTP TRACE request. 
   * Sends the received HTTP headers back to the client as the message body of the response.
   */
  private void trace() throws IOException {
    // Rebuild the header from the array into a string re-adding the new line chars.
    String received = "";
    for (String l : headerLines) {
      received += l + "\r\n";
    }
    received += "\r\n";

    respond("HTTP/1.1 200 OK\r\n"
      + "Date: " + date + "\r\n"
      + "Connection: close\r\n"
      + "Server: bws\r\n"
      + "Content-Length: " + received.length() + "\r\n"
      + "Content-Type: message/http\r\n"
      + "\r\n",
      new ByteArrayInputStream(received.getBytes()));
  }

  /**
   * Builds the response header from a get request for the given requested file.
   * @return The header string.
   */
  private String buildGetHeader() throws NotFoundException {
    String resourcePath = resourceFile.getAbsolutePath();
    int checkDefault = -1;

    WebServer.logln("Requested file: " + resourcePath);
    WebServer.log("Checking file... ");

    while (true) {
      if (resourceFile.exists() && resourceFile.isFile() && resourceFile.canRead()) {
        WebServer.logln("Found");
        return "HTTP/1.1 200 OK\r\n"
          + "Date: " + date + "\r\n"
          + "Connection: close\r\n"
          + "Server: bws\r\n"
          + "Last-Modified: " + dateOptionFormat.format(new Date(resourceFile.lastModified())) + " GMT\r\n"
          + "Content-Length: " + resourceFile.length() + "\r\n"
          + "Content-Type: " + getContentType(resourceFile) + "\r\n"
          + "\r\n";
      } else {
        // If the file object points to a directory check that directory for the default files.
        if (++checkDefault < WebServer.DEFAULT_FILES.length) {
          WebServer.log("Not Found\r\nChecking " + WebServer.DEFAULT_FILES[checkDefault] + "... ");
          resourceFile = new File(resourcePath + File.separator + WebServer.DEFAULT_FILES[checkDefault]);
        } else {
          // Once it has checked all the default files and not found them.
          WebServer.logln("Not found");
            throw new NotFoundException();
        }
      }
    }
  }

  /**
   * Given a file object return the MIME type of that file.
   * Only implements image/jpeg, image/png, text/css, text/javascript,
   * text/plain and text/html for all other files.
   * @param resource The file object.
   * @return The MIME type.
   */
  private String getContentType(File resource) {
    try {
      String path = resource.getAbsolutePath();
      String ext = path.substring(path.lastIndexOf(".") + 1);

      if (ext.equals("jpeg") || ext.equals("jpg")) {
        return "image/jpeg";
      } else if (ext.equals("png")) {
        return "image/png";
      } else if (ext.equals("gif")) {
        return "image/gif";
      } else if (ext.equals("css")) {
        return "text/css";
      } else if (ext.equals("js")) {
        return "text/javascript";
      } else if (ext.equals("txt")) {
        return "text/plain";
      } else {
        return "text/html";
      }
    } catch (IndexOutOfBoundsException ex) {
      // If a file ends in a dot, which it shouldn't, substring(... + 1) will fail.
      return "text/html"; // assume default
    } 
  }

  /**
   * Sends a given header and resource to the client over the socket.
   * @param header The HTTP header to send to the client.
   * @param resourceStream An input stream pointing to the resource to send to the client.
   */
  private void respond(String header, InputStream resourceStream) throws IOException {
    WebServer.logln("Sending response...");
    WebServer.log(header);
    toClient = sock.getOutputStream();
    toClient.write(header.getBytes());
    WebServer.logln("Sent");

    // Send the file, if there is one
    if (resourceStream != null) {
      final int BSIZE = 524288; // 524kB
      byte[] fileBytes = new byte[BSIZE];

      WebServer.log("Sending file... ");

      while (true)
      {
        int rc = resourceStream.read(fileBytes, 0, BSIZE);
        if (rc <= 0) break;
        toClient.write(fileBytes, 0, rc);
      }
      resourceStream.close();

      WebServer.logln("Sent");
    }
    logRequest(header);
  }

  /**
   * Responds with the given header and resource however checks whether the method is HEAD
   * and therefore only sends the resource if the method is not HEAD.
   * For use in the exception handlers so the exception message is not sent when the method is HEAD.
   * @param header The http header.
   * @param resourceStream The input stream of the file to send to the client.
   */
  private void respondWithHeadCheck(String header, InputStream resourceStream) throws IOException {
    if (method != null && method.equals("HEAD")) {
      respond(header, null);
    } else {
      respond(header, resourceStream);
    }
  }
  
  /**
   * Writes the request information to a log file. The log contains each connection's timestamp, ip, 
   * requested resource and the returned response code.
   * @param responseHeader The header that was sent back to the client, ie 200 OK, 404 Not Found etc.
   */
  private synchronized void logRequest(String responseHeader) {
    // Extract just the returned code and description from the header.
    String response = responseHeader.substring(responseHeader.indexOf(" ") + 1, responseHeader.indexOf("\r\n"));
    File serverLog = null;
    try {
      serverLog = new File(WebServer.SERVER_LOG);
      serverLog.createNewFile();
      PrintStream p = new PrintStream(new FileOutputStream(serverLog, true));
      
      SimpleDateFormat df = new SimpleDateFormat("dd/MMM/yyyy HH:mm:ss");
      String str = df.format(timestamp.getTime()) + " - " + sock.getInetAddress().getHostAddress() 
              + " \"" + (headerLines.length > 0 ? headerLines[0] : "") + "\" " + response;
      WebServer.logln("Logged: " + str);
      p.println(str);

      p.close();
    } catch (FileNotFoundException fnf) {
      WebServer.logln("ERROR: Cannot find " + (serverLog != null ? serverLog.getAbsolutePath() : "server log file."));
    } catch (IOException io) {
      WebServer.logln("ERROR: Failed to write request to the log file.");
    }
  }

  /**
   * Closes the resources open on the socket and then the socket itself.
   */
  private void close() {
    try {
      if (fromClient != null) fromClient.close();
    } catch (IOException ex) { 
      WebServer.logln("ERROR: Failed to close the stream from the client properly."); 
    }

    try {
      if (toClient != null) toClient.close();
    } catch (IOException ex) { 
      WebServer.logln("ERROR: Failed to close the stream to the client properly."); 
    }

    try {
      if (sock != null) sock.close();
    } catch (IOException ex) { 
      WebServer.logln("ERROR: Failed to close the socket properly."); 
    }
    WebServer.logln("Connection closed");
  }

  /**
   * Checks whether a given method is a valid HTTP method.
   * @param value The method to check.
   * @return True when the method is a valid HTTP method, false if not.
   */
  public static boolean validMethod(String value) {
  	for (String method : VALID_METHODS) {
  		if (method.equals(value)) {
  			return true;
  		}
  	}
  	logln("Method not valid: " + value);
  	return false;
  }

  /**
   * Logs the given string to the terminal if logging is turned on.
   * @param line The line to log.
   */
  public static void log(String line) {
    if (LOGGING) {
  		System.out.print(line);
  	}
  }

  /**
   * Logs the given string and new line to the terminal if logging is turned on.
   * @param line The line to log.
   */
  public static void logln(String line) {
  	if (LOGGING) {
  			System.out.println("[" + Thread.currentThread().getId() + "] " + line);
  	}
  }

  // --- Exceptions ---
  /**
   * Thrown when the requested resource does not exist.
   */
  class NotFoundException extends Exception {
    public NotFoundException() {
      super();
    }
  }

  /**
   * Thrown when a HTTP request is made that is not supported.
   */
  class NotImplementedException extends Exception {
    public NotImplementedException() {
      super();
    }
  }

  /**
   * Thrown when an invalid request is made.
   */
  class BadRequestException extends Exception {
    public BadRequestException() {
      super();
    }
  }
}