package azkaban.project;

import java.io.File;
import java.io.FileFilter;
import java.io.FileWriter;
import java.io.IOException;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;

import org.apache.log4j.Logger;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import azkaban.flow.Flow;
import azkaban.user.Permission;
import azkaban.user.Permission.Type;
import azkaban.user.User;
import azkaban.utils.DirectoryFlowLoader;
import azkaban.utils.JSONUtils;
import azkaban.utils.Props;

/**
 * A project loader that stores everything on local file system.
 * The following global parameters should be set -
 * file.project.loader.path - The project install path where projects will be loaded installed to.
 */
public class FileProjectManager implements ProjectManager {
	public static final String DIRECTORY_PARAM = "file.project.loader.path";
	private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormat.forPattern("yyyy-MM-dd-HH:mm.ss.SSS");
	private static final String PROPERTIES_FILENAME = "project.json";
	private static final String PROJECT_DIRECTORY = "src";
	private static final String FLOW_EXTENSION = ".flow";
	private static final Logger logger = Logger.getLogger(FileProjectManager.class);
	private static final int IDLE_SECONDS = 120;
	private ConcurrentHashMap<String, Project> projects = new ConcurrentHashMap<String, Project>();
	private CacheManager manager = CacheManager.create();
	private Cache sourceCache;
	
	private File projectDirectory;

	public FileProjectManager(Props props) {
		setupDirectories(props);
		loadAllProjects();
		setupCache();
	}

	private void setupCache() {
		CacheConfiguration cacheConfig = 
				new CacheConfiguration("propsCache", 2000)
					.memoryStoreEvictionPolicy(MemoryStoreEvictionPolicy.LRU)
					.overflowToDisk(false)
					.eternal(false)
					.timeToIdleSeconds(IDLE_SECONDS)
					.diskPersistent(false)
					.diskExpiryThreadIntervalSeconds(0);

		sourceCache = new Cache(cacheConfig);
		manager.addCache(sourceCache);
	}
	
	private void setupDirectories(Props props) {
		String projectDir = props.getString(DIRECTORY_PARAM);
		logger.info("Using directory " + projectDir + " as the project directory.");
		projectDirectory = new File(projectDir);
		if (!projectDirectory.exists()) {
			logger.info("Directory " + projectDir + " doesn't exist. Creating.");
			if (projectDirectory.mkdirs()) {
				logger.info("Directory creation was successful.");
			}
			else {
				throw new RuntimeException("FileProjectLoader cannot create directory " + projectDirectory);
			}
		}
		else if (projectDirectory.isFile()) {
			throw new RuntimeException("FileProjectManager directory " + projectDirectory + " is really a file.");
		}
	}

	private void loadAllProjects() {
		File[] directories = projectDirectory.listFiles();
		
		for (File dir: directories) {
			if (!dir.isDirectory()) {
				logger.error("ERROR loading project from " + dir.getPath() + ". Not a directory." );
			}
			else {
				File propertiesFile = new File(dir, PROPERTIES_FILENAME);
				if (!propertiesFile.exists()) {
					logger.error("ERROR loading project from " + dir.getPath() + ". Project file " + PROPERTIES_FILENAME + " not found." );
				}
				else {
					Object obj = null;
					try {
						obj = JSONUtils.parseJSONFromFile(propertiesFile);
					} catch (IOException e) {
						logger.error("ERROR loading project from " + dir.getPath() + ". Project file " + PROPERTIES_FILENAME + " couldn't be read.", e );
						continue;
					}

					Project project = Project.projectFromObject(obj);
					logger.info("Loading project " + project.getName());
					projects.put(project.getName(), project);
				
					String source = project.getSource();
					if (source == null) {
						logger.info(project.getName() + ": No flows uploaded");
						continue;
					}
					
					File projectDir = new File(dir, source);
					if (!projectDir.exists()) {
						logger.error("ERROR project source dir " + projectDir + " doesn't exist.");
					}
					else if (!projectDir.isDirectory()) {
						logger.error("ERROR project source dir " + projectDir + " is not a directory.");
					}
					else {
						File[] flowFiles = projectDir.listFiles(new SuffixFilter(FLOW_EXTENSION));
						Map<String, Flow> flowMap = new LinkedHashMap<String, Flow>();
						for (File flowFile: flowFiles) {
							Object objectizedFlow = null;
							try {
								objectizedFlow = JSONUtils.parseJSONFromFile(flowFile);
							} catch (IOException e) {
								logger.error("Error parsing flow file " + flowFile.toString(), e);
								continue;
							}

							//Recreate Flow
							Flow flow = null;
							
							try {
								flow = Flow.flowFromObject(objectizedFlow);
							}
							catch (Exception e) {
								logger.error("Error loading flow " + flowFile.getName() + " in project " + project.getName(), e);
								continue;
							}
							logger.debug("Loaded flow " + project.getName() + ": " + flow.getId());
							flow.initialize();
							
							flowMap.put(flow.getId(), flow);
						}

						synchronized (project) {
							project.setFlows(flowMap);
						}
					}
				}
			}
		}
	}
	
	public List<String> getProjectNames() {
		return new ArrayList<String>(projects.keySet());
	}
	
	public List<Project> getProjects(User user) {
		ArrayList<Project> array = new ArrayList<Project>();
		for(Project project : projects.values()) {
			Permission perm = project.getUserPermission(user);

			if (perm != null && (perm.isPermissionSet(Type.ADMIN) || perm.isPermissionSet(Type.READ))) {
				array.add(project);
			}
		}
		return array;
	}

	public Project getProject(String name, User user) {
		Project project = projects.get(name);
		if (project != null) {
			Permission perm = project.getUserPermission(user);
			if (perm.isPermissionSet(Type.ADMIN) || perm.isPermissionSet(Type.READ)) {
				return project;
			}
			else {
				throw new AccessControlException("Permission denied. Do not have read access.");
			}
		}
		return project;
	}

	public void uploadProject(String projectName, File dir, User uploader, boolean force) throws ProjectManagerException {
		logger.info("Uploading files to " + projectName);
		Project project = projects.get(projectName);
		
		if (project == null) {
			throw new ProjectManagerException("Project not found.");
		}
		if (!project.hasPermission(uploader, Type.WRITE)) {
			throw new AccessControlException("Permission denied. Do not have write access.");
		}

		List<String> errors = new ArrayList<String>();
		DirectoryFlowLoader loader = new DirectoryFlowLoader(logger);
		loader.loadProjectFlow(dir);
		Map<String, Flow> flows = loader.getFlowMap();
				
		File projectPath = new File(projectDirectory, projectName);
		File installDir = new File(projectPath, FILE_DATE_FORMAT.print(System.currentTimeMillis()));
		if (!installDir.mkdir()) {
			throw new ProjectManagerException("Cannot create directory in " + projectDirectory);
		}
		
		for (Flow flow: flows.values()) {
			try {
				if (flow.getErrors() != null) {
					errors.addAll(flow.getErrors());
				}
				flow.initialize();

				writeFlowFile(installDir, flow);
			} catch (IOException e) {
				throw new ProjectManagerException(
						"Project directory " + projectName + " cannot be created in " + projectDirectory, e);
			}
		}
		
		File destDirectory = new File(installDir, PROJECT_DIRECTORY);
		dir.renameTo(destDirectory);
		
		// We install only if the project is not forced install or has no errors
		if (force || errors.isEmpty()) {
			// We synchronize on project so that we don't collide when uploading.
			synchronized (project) {
				logger.info("Uploading files to " + projectName);
				project.setSource(installDir.getName());
				project.setLastModifiedTimestamp(System.currentTimeMillis());
				project.setLastModifiedUser(uploader.getUserId());
				project.setFlows(flows);
			}
			
			try {
				writeProjectFile(projectPath, project);
			} catch (IOException e) {
				throw new ProjectManagerException(
						"Project directory " + projectName + 
						" cannot be created in " + projectDirectory, e);
			}
		}
		else {
			logger.info("Errors found loading project " + projectName);
			StringBuffer bufferErrors = new StringBuffer();
			for(String error : errors) {
				bufferErrors.append(error);
				bufferErrors.append("\n");
			}
			
			throw new ProjectManagerException(bufferErrors.toString());
		}
	}

	@Override
	public synchronized Project createProject(String projectName, String description, User creator) throws ProjectManagerException {
		if (projectName == null || projectName.trim().isEmpty()) {
			throw new ProjectManagerException("Project name cannot be empty.");
		}
		else if (description == null || description.trim().isEmpty()) {
			throw new ProjectManagerException("Description cannot be empty.");
		}
		else if (creator == null) {
			throw new ProjectManagerException("Valid creator user must be set.");
		}
		else if (!projectName.matches("[a-zA-Z][a-zA-Z_0-9|-]*")){
			throw new ProjectManagerException("Project names must start with a letter, followed by any number of letters, digits, '-' or '_'.");
		}
	
		if (projects.contains(projectName)) {
			throw new ProjectManagerException("Project already exists.");
		}
		
		File projectPath = new File(projectDirectory, projectName);
		if (projectPath.exists()) {
			throw new ProjectManagerException("Project already exists.");
		}
		
		if(!projectPath.mkdirs()) {
			throw new ProjectManagerException(
					"Project directory " + projectName + 
					" cannot be created in " + projectDirectory);
		}
		
		Permission perm = new Permission(Type.ADMIN);
		long time = System.currentTimeMillis();
		
		Project project = new Project(projectName);
		project.setUserPermission(creator.getUserId(), perm);
		project.setDescription(description);
		project.setCreateTimestamp(time);
		project.setLastModifiedTimestamp(time);
		project.setLastModifiedUser(creator.getUserId());
		
		logger.info("Trying to create " + project.getName() + " by user " + creator.getUserId());
		try {
			writeProjectFile(projectPath, project);
		} catch (IOException e) {
			throw new ProjectManagerException(
					"Project directory " + projectName + 
					" cannot be created in " + projectDirectory, e);
		}
		projects.put(projectName, project);
		return project;
	}
	
	private synchronized void writeProjectFile(File directory, Project project) throws IOException {
		Object object = project.toObject();
		File tmpFile = File.createTempFile("project-",".json", directory);
		
		if (tmpFile.exists()) {
			tmpFile.delete();
		}
		
		logger.info("Writing project file " + tmpFile);
		String output = JSONUtils.toJSON(object, true);
		
		FileWriter writer = new FileWriter(tmpFile);
		try {
			writer.write(output);
		} catch (IOException e) {
			if (writer != null) {
				writer.close();
			}
			
			throw e;
		}
		writer.close();
		
		File projectFile = new File(directory, PROPERTIES_FILENAME);
		File swapFile = new File(directory, PROPERTIES_FILENAME + "_old");
		
		projectFile.renameTo(swapFile);
		tmpFile.renameTo(projectFile);
		swapFile.delete();
	
	}
	
	private void writeFlowFile(File directory, Flow flow) throws IOException {
		Object object = flow.toObject();
		String filename = flow.getId() + FLOW_EXTENSION;
		File outputFile = new File(directory, filename);
		File oldOutputFile = new File(directory, filename + ".old");
		
		if (outputFile.exists()) {
			outputFile.renameTo(oldOutputFile);
		}
		
		logger.info("Writing flow file " + outputFile);
		String output = JSONUtils.toJSON(object, true);
		
		FileWriter writer = new FileWriter(outputFile);
		try {
			writer.write(output);
		} catch (IOException e) {
			if (writer != null) {
				writer.close();
			}
			
			throw e;
		}
		writer.close();
		
		if (oldOutputFile.exists()) {
			oldOutputFile.delete();
		}
	}
	
	public Props getProperties(String projectName, String source, User user) throws ProjectManagerException {
		Project project = projects.get(projectName);
		if (project == null) {
			throw new ProjectManagerException("Project " + project + " cannot be found.");
		}
		if (!project.hasPermission(user, Type.READ)) {
			throw new AccessControlException("Permission denied. Do not have read access.");
		}

		String mySource = projectName + File.separatorChar + project.getSource() + File.separatorChar + "src" + File.separatorChar + source;
		Element sourceElement = sourceCache.get(mySource);

		if (sourceElement != null) {
			return Props.clone((Props)sourceElement.getObjectValue());
		}
		
		File file = new File(projectDirectory, mySource);
		if (!file.exists()) {
			throw new ProjectManagerException("Source file " + file.getAbsolutePath() + " doesn't exist.");
		}

		try {
			Props props = new Props((Props)null, file);
			return props;
		} catch (IOException e) {
			throw new ProjectManagerException("Error loading file " + file.getPath(), e);
		}
	}
	
	@Override
	public synchronized Project removeProject(String projectName, User user) {
		return null;
	}

	private static class SuffixFilter implements FileFilter {
		private String suffix;
		
		public SuffixFilter(String suffix) {
			this.suffix = suffix;
		}
	
		@Override
		public boolean accept(File pathname) {
			String name = pathname.getName();
			
			return pathname.isFile() && !pathname.isHidden() && name.length() > suffix.length() && name.endsWith(suffix);
		}
	}

	@Override
	public void commitProject(String projectName) throws ProjectManagerException {
		Project project = projects.get(projectName);
		if (project == null) {
			throw new ProjectManagerException("Project " + projectName + " doesn't exist.");
		}
		
		File projectPath = new File(projectDirectory, projectName);
		try {
			writeProjectFile(projectPath, project);
		}
		catch (IOException e) {
			throw new ProjectManagerException("Error committing project " + projectName, e);
		}
	}


}