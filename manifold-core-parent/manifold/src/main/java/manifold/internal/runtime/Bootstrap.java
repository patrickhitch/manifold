package manifold.internal.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Hashtable;
import manifold.internal.host.ManifoldHost;

/**
 */
public class Bootstrap
{
  public static final String MAN_CLASS_PROTOCOL = "manifoldclass";
  private static final String PROTOCOL_PACKAGE = "manifold.internal.runtime.protocols";

  private static Boolean CAN_WRAP = Boolean.FALSE; //= null;

  private static void setupLoaderChainWithManifoldUrl( ClassLoader loader )
  {
    if( !canWrapChain( loader ) )
    {
      //System.out.println( "WARNING: Can't wrap loader: " + loader.getClass().getTypeName() );
      return;
    }

    UrlClassLoaderWrapper wrapped = UrlClassLoaderWrapper.wrapIfNotAlreadyVisited( loader );
    if( wrapped == null )
    {
      return;
    }
    addManifoldClassUrl( wrapped );
    if( canWrapChain() )
    {
      if( loader != ClassLoader.getSystemClassLoader() )
      { // we don't bother messing with any loaders above the system loader e.g., ExtClassLoader
        loader = loader.getParent();
        if( loader != null )
        {
          setupLoaderChainWithManifoldUrl( loader );
        }
      }
    }
  }

  /*
    We don't currently wrap the chain of loaders for WebSphere or WebLogic or JBoss
    because they use "module" class loaders that are not URLClassLoader-like.  We
    will handle them separately.
   */
  private static boolean canWrapChain( ClassLoader loader )
  {
    if( loader == null )
    {
      return false;
    }
    UrlClassLoaderWrapper wrapped = UrlClassLoaderWrapper.wrap( loader );
    boolean bSysLoader = loader == ClassLoader.getSystemClassLoader();
    if( bSysLoader )
    {
      return wrapped != null;
    }
    loader = loader.getParent();
    return wrapped != null && canWrapChain( loader );
  }

  private static void addManifoldClassUrl( UrlClassLoaderWrapper urlLoader )
  {
    try
    {
      URL url = makeUrl( urlLoader.getLoader() );
      if( !urlLoader.getURLs().contains( url ) )
      {
        urlLoader.addURL( url );
      }
    }
    catch( MalformedURLException e )
    {
      throw new RuntimeException( e );
    }
  }

  private static URL makeUrl( ClassLoader loader ) throws MalformedURLException
  {
    int loaderAddress = System.identityHashCode( loader );
    String spec = MAN_CLASS_PROTOCOL + "://" + loaderAddress + "/";
    URL url;
//    try
//    {
//      url = new URL( null, spec );
//    }
//    catch( Exception e )
//    {
    // If our Handler class is not in the system loader and not accessible within the Caller's
    // classloader from the URL constructor (3 activation records deep), then our Handler class
    // is not loadable by the URL class, so we do this...

    addOurProtocolHandler();
    url = new URL( null, spec );
//    }
    return url;
  }

  public static void addOurProtocolHandler()
  {
    try
    {
      Field field = URL.class.getDeclaredField( "handlers" );
      field.setAccessible( true );
      Method put = Hashtable.class.getMethod( "put", Object.class, Object.class );
      Field instanceField = Class.forName( "manifold.internal.runtime.protocols.Handler" ).getField( "INSTANCE" );
      Object handler = instanceField.get( null );
      put.invoke( field.get( null ), MAN_CLASS_PROTOCOL, handler );
    }
    catch( Exception e )
    {
      throw new IllegalStateException( "Failed to configure manifold protocol handler", e );
    }
  }

  private static void removeOurProtocolHandler()
  {
    try
    {
      Field field = URL.class.getDeclaredField( "handlers" );
      field.setAccessible( true );
      Method remove = Hashtable.class.getMethod( "remove", Object.class );
      remove.invoke( field.get( null ), MAN_CLASS_PROTOCOL );
    }
    catch( Exception e )
    {
      throw new IllegalStateException( "Failed to cleanup manifold protocol handler", e );
    }
  }

  private static boolean addOurProtocolPackage()
  {
    // Do not add protocol package since OSGi implementation of URLStreamFactory
    // first delegates to those and only then calls service from Service Registry
    String strProtocolProp = "java.protocol.handler.pkgs";
    String protocols = PROTOCOL_PACKAGE;
    String oldProp = System.getProperty( strProtocolProp );
    if( oldProp != null )
    {
      if( oldProp.contains( PROTOCOL_PACKAGE ) )
      {
        return false;
      }
      protocols += '|' + oldProp;
    }
    System.setProperty( strProtocolProp, protocols );
    return true;
  }

  private static void removeOurProtocolPackage()
  {
    String strProtocolProp = "java.protocol.handler.pkgs";
    String protocols = System.getProperty( strProtocolProp );
    if( protocols != null )
    {
      // Remove our protocol from the list
      protocols = protocols.replace( PROTOCOL_PACKAGE + '|', "" );
      System.setProperty( strProtocolProp, protocols );
    }
  }

  // flag to prevent re-entry
  private static boolean _busy = false;
  //!! Do Not Rename or Remove this method.  Calls to it are generated by the compiler and javac hook.
  public synchronized static boolean init()
  {
    if( _busy )
    {
      return false;
    }

    _busy = true;
    try
    {
      if( addOurProtocolPackage() )
      {
        ManifoldHost.bootstrap();
      }
      ClassLoader loader = ManifoldHost.getActualClassLoader();
      if( loader != null )
      {
        setupLoaderChainWithManifoldUrl( loader );
        return true;
      }
      return false;
    }
    finally
    {
      _busy = false;
    }
  }

  public static boolean canWrapChain()
  {
    return CAN_WRAP == null ? CAN_WRAP = canWrapChain( ManifoldHost.getActualClassLoader() ) : CAN_WRAP;
  }

  @SuppressWarnings("unused")
  public synchronized static void cleanup()
  {
    removeOurProtocolPackage();
    removeOurProtocolHandler();
  }
}
