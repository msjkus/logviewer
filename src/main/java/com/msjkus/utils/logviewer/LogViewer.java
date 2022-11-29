package com.msjkus.utils.logviewer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet(name = "logviewer", urlPatterns = {"", "/", LogViewer.SERVLET_PATH})
public class LogViewer extends HttpServlet {

	@Override
	public void init() throws ServletException {
		contextPath = this.getServletContext().getContextPath();
		try (InputStream is = this.getClass().getClassLoader(). getResourceAsStream(CONF_FILENAME)) {
			Properties prop = new Properties();
			prop.load(is);
			String strReadBytes = (String) prop.get("com.msjkus.logviewer.logs.read.bytes");
			String strMetaRefresh = (String) prop.get("com.msjkus.logviewer.logs.read.refresh");
			String strDir = (String) prop.get("com.msjkus.logviewer.logs.dir");
			String user = (String) prop.get("com.msjkus.logviewer.security.user");
			String password = (String) prop.get("com.msjkus.logviewer.security.password");
			Path dir = Paths.get(strDir);
			if (Files.exists(dir)) {
				logsDir = dir;
			}
			credentials = new String(Base64.getEncoder().encode((user + ":" + password).getBytes()));
			readBytes = Integer.parseInt(strReadBytes);
			metaRefresh = Integer.parseInt(strMetaRefresh);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		boolean proceed = checkAuthorizaiton(req, resp);
		if (!proceed) return;

		String file = req.getParameter("file");
		int bytes = (req.getParameter("bytes") != null) ? Integer.parseInt(req.getParameter("bytes")) : this.readBytes;
		int refresh = (req.getParameter("refresh") != null) ? Integer.parseInt(req.getParameter("refresh")) : this.metaRefresh;
		if (file != null) {
			showFile(resp, file, bytes, refresh);
			return;
		}

		listLogs(resp);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		doGet(req, resp);
	}

	private boolean checkAuthorizaiton(HttpServletRequest req, HttpServletResponse resp) {
		boolean proceed = false;
		String header = req.getHeader("Authorization");
		if (header == null) {
			resp.setHeader("WWW-Authenticate", "Basic realm=\"Logviewer\"");
			resp.setStatus(401);
		} else {
			header = header.replace("Basic ", "");
			if (header.equals(credentials)) {
				proceed = true;
			} else {
				resp.setHeader("WWW-Authenticate", "Basic realm=\"Logviewer\"");
				resp.setStatus(401);
			}
		}
		return proceed;
	}

	private void showFile(HttpServletResponse resp, String fileName, int readBytes, int metaRefresh) throws IOException {
		Path file = Paths.get(logsDir + File.separator + fileName);
		if (!Files.exists(file)) {
			printHTML(resp, "", fileName + " NOT FOUND");
		}
		BasicFileAttributes fa = Files.readAttributes(file, BasicFileAttributes.class);
		long readFrom = fa.size() - readBytes;
		if (readFrom < 0) readFrom = 0;
		String body = "<b>" + fileName + "</b><br>";
		try (RandomAccessFile randomAccessFile = new RandomAccessFile(file.toFile(), "r")) {
			randomAccessFile.seek(readFrom);
			String line = null;
			while((line = randomAccessFile.readLine()) != null) {
				line = escapeHtml(line);
				body += line + "<br>";
			}
		}
		printHTML(resp, "<meta http-equiv='refresh' content='" + metaRefresh + "'>", body);
	}

	private String escapeHtml(String line) {
		return line.chars()
				.mapToObj(ch -> ch > 127 || "\"'<>&".indexOf(ch) != -1 ? "&#" + ch + ";" : String.valueOf((char) ch))
				.collect(Collectors.joining());
	}

	private void listLogs(HttpServletResponse resp) throws IOException {
		if (logsDir == null) {
			printHTML(resp, "", "NO LOGS DIR CONFIGURED");
		}

		try (Stream<Path> entries = Files.list(logsDir)) {
			List<Path> logFiles = entries
					.filter(path -> !path.toFile().isDirectory())
					.collect(Collectors.toList());
			logFiles.sort((file1, file2) -> {
				long diff;
				try {
					diff = Files.readAttributes(file2, BasicFileAttributes.class).lastModifiedTime().toMillis() - Files.readAttributes(file1, BasicFileAttributes.class).lastModifiedTime().toMillis();
				} catch (IOException e) {
					return 0;
				}
				if (diff > 0)
					return 1;
				if (diff < 0)
					return -1;
				return 0;
			});
			String filesHtml = "<table width='100%' cellpadding='2' cellspacing='0' border='1'>"
					+ "<tr>"
					+ "<td align='center'> <b>FILE NAME</b> </td><td align='center'> <b>LAST MODIFIED</b> </td><td align='center'> <b>SIZE</b> </td>"
					+ "</tr>"
					;
			for (Path logFile : logFiles) {
				BasicFileAttributes fa = Files.readAttributes(logFile, BasicFileAttributes.class);
				filesHtml += "<tr>"
						+ "<td><a target='_" + logFile.getFileName() + "' href='" + contextPath + SERVLET_PATH + "?bytes=" + readBytes + "&refresh=" + metaRefresh + "&file=" + logFile.getFileName() + "'>" + logFile.getFileName() + "</a></td>"
						+ "<td>" + fa.lastModifiedTime() + "</td>"
						+ "<td>" + fa.size() + "</td>"
						+ "</tr>"
						;
			}
			filesHtml += "</table>";
			
			printHTML(resp, "", filesHtml);
		}
		
	}

	private void printHTML(HttpServletResponse resp, String headContent, String htmlBody) throws IOException {
		String output = "<html>"
				+ "<head>"
				+ "    <title>LogViewer</title>"
				+		headContent
				+ "</head>"
				+ "    <body>"
				+ 			htmlBody
				+ "    </body>"
				+ "</html>";
		resp.getWriter().print(output);
	}

	protected static final String SERVLET_PATH = "/logs";
	
	private Path logsDir = null;
	private int readBytes = 10240;
	private int metaRefresh = 5;
	private String contextPath = "";
	private String credentials = "YWRtaW46YWRtaW4=";

	private static final String CONF_FILENAME = "logviewer.properties";
	private static final long serialVersionUID = 8362213903007652264L;

}
