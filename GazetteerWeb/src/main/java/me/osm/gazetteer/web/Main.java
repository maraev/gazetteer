package me.osm.gazetteer.web;

import java.io.FileNotFoundException;
import java.io.IOException;

import me.osm.gazetteer.web.postprocessor.AllowOriginPP;
import me.osm.gazetteer.web.postprocessor.LastModifiedHeaderPostprocessor;
import me.osm.gazetteer.web.postprocessor.MarkHeaderPostprocessor;
import me.osm.gazetteer.web.serialization.SerializationProvider;
import me.osm.gazetteer.web.utils.OSMDocSinglton;

import org.restexpress.RestExpress;
import org.restexpress.util.Environment;

public class Main {
	
	private static class ShutDownListener implements Runnable
	{
	    @Override
	    public void run()
	    {
	    	ESNodeHodel.stopNode();
	    	server.shutdown();
	    }
	}
	
	private static RestExpress server;
	private volatile static Configuration config;
	
	public static void main(String[] args) throws Exception {
		
		config = loadEnvironment(args);
		ESNodeHodel.getClient();

		OSMDocSinglton.initialize(config.getPoiCatalogPath());
		
		RestExpress.setSerializationProvider(new SerializationProvider());
		
		server = new RestExpress()
				.setName(config.getName())
				.addPostprocessor(new LastModifiedHeaderPostprocessor())
				.addPostprocessor(new AllowOriginPP())
				.addPostprocessor(new MarkHeaderPostprocessor())
				.addPreprocessor(new BasikAuthPreprocessor(null));

		Routes.defineRoutes(server);
		
		server.addMessageObserver(new HttpLogger());
		
		
		server.bind(config.getPort());
		Runtime runtime = Runtime.getRuntime();
		Thread thread = new Thread(new ShutDownListener());
        runtime.addShutdownHook(thread);
	}

	private static Configuration loadEnvironment(String[] args)
			throws FileNotFoundException, IOException {
		if (args.length > 0) {
			return Environment.from(args[0], Configuration.class);
		}

		try {
			return Environment.fromDefault(Configuration.class);
		}
		catch (Exception e) {
			return new Configuration();
		}
	}
	
	public static Configuration config() {
		return config;
	}
	
	
	
}
