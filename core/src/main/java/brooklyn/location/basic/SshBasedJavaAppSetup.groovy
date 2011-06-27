package brooklyn.location.basic

import java.util.Map

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.entity.Entity

// TODO OS-X failure, if no recent command line ssh
// ssh_askpass: exec(/usr/libexec/ssh-askpass): No such file or directory
// Permission denied, please try again.
// ssh_askpass: exec(/usr/libexec/ssh-askpass): No such file or directory
// Received disconnect from ::1: 2: Too many authentication failures for alex 

public abstract class SshBasedJavaAppSetup {
    static final Logger log = LoggerFactory.getLogger(SshBasedJavaAppSetup.class)
 
	Entity entity
	String appBaseDir
	String brooklynBaseDir = "/tmp/brooklyn"
	String installsBaseDir = brooklynBaseDir+"/installs"
	
	public SshBasedJavaAppSetup(Entity entity) {
		this.entity = entity
		appBaseDir = brooklynBaseDir + "/" + "app-"+entity.getApplication()?.id
	}

	/** convenience to generate string -Dprop1=val1 -Dprop2=val2 for use with java */		
	public static String toJavaDefinesString(Map m) {
		StringBuffer sb = []
		m.each { sb.append("-D"); sb.append(it.key); if (it.value!='') { sb.append('=\''); sb.append(it.value); sb.append('\' ') } else { sb.append(' ') } }
		return sb.toString().trim()
		//TODO - try the following instead
		//return m.collect( { "-D"+it.key+(it.value?:"='"+it.value+"'"} ).join(" ")
	}
 
	/** convenience to record a value on the location to ensure each instance gets a unique value */
	protected int getNextValue(String field, int initial) {
		def v = entity.attributes[field]
		if (!v) {
			log.debug "retrieving {}, {}", field, entity.location.attributes
			synchronized (entity.location) {
				v = entity.location.attributes["next_"+field] ?: initial
				entity.location.attributes["next_"+field] = (v+1)
			}
			log.debug "retrieved {}, {}", field, entity.location.attributes
			entity.attributes[field] = v
		}
		v
	}
 
	public Map getJvmStartupProperties() {
		[:] + getJmxConfigOptions()
	}
 
	public int getJmxPort() {
		log.debug "setting jmxHost on $entity as {}", entity.machine.host
		entity.attributes.jmxHost = entity.machine.host.hostName
		getNextValue("jmxPort", 32199)
	}
 
	/**
	 * Return the JMX configuration properties used to start the service.
	 * 
	 * TODO security!
	 */
	public Map getJmxConfigOptions() {
		[
          'com.sun.management.jmxremote':'',
		  'com.sun.management.jmxremote.port':getJmxPort(),
		  'com.sun.management.jmxremote.ssl':false,
		  'com.sun.management.jmxremote.authenticate':false
		]
	}
	protected String makeInstallScript(String ...lines) { 
		String result = """\
if [ -f $installDir/../INSTALL_COMPLETION_DATE ] ; then echo software is already installed ; exit ; fi
mkdir -p $installDir && \\
cd $installDir/.. && \\
""";
		lines.each { result += it + "&& \\\n" }
		// TODO use .collect, as above
		result += """\
date > INSTALL_COMPLETION_DATE
exit
""" 
	}

	public String getInstallScript() { null }
 
	public abstract String getRunScript();
	
	/**
	 * Should return script to run at remote server to determine whether process is running.
	 * 
	 * Script should return 0 if healthy, 1 if stopped, any other code if not healthy
	 */
	public abstract String getCheckRunningScript();
	
	public void start(SshMachine machine) {
        log.info "starting entity {} on machine {}", entity, machine
		synchronized (getClass()) {
			String s = getInstallScript()
			if (s) {
				int result = machine.run(out:System.out, s)
				if (result) throw new IllegalStateException("failed to start $entity (exit code $result)")
			}
		}

		def result = machine.run(out:System.out, getRunScript())
		if (result) throw new IllegalStateException("failed to start $entity (exit code $result)")
	}
 
	public boolean isRunning(SshMachine machine) {
		def result = machine.run(out:System.out, getCheckRunningScript())
		if (result==0) return true
		if (result==1) return false
		throw new IllegalStateException("$entity running check gave result code $result")
	}
}
