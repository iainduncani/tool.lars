# TODO Eclipse

* Get Eclipse IDE For Java EE Developers from
https://www.eclipse.org/downloads/. At the time of writing, we are
using Eclipse Luna SR1a (4.4.1a).

* Install Gradle Integration for Eclipse from The Eclipse Marketplace. For example, for Eclipse Luna, it can be found at the following location: http://marketplace.eclipse.org/content/gradle-integration-eclipse-44

* Install the Liberty Profile Developer Tools into Eclipse. You can find the Install button on this page: https://developer.ibm.com/wasdev/downloads/liberty-profile-using-eclipse/

* Import the LARS projects into Eclipse: 
  * File -> Import -> Grade -> Gradle Project
  * Give the root directory of your tool.lars source tree
  * Click Build Model
  * Select the root project (tool.lars) in the resulting tree to select all projects
  * Uncheck <TODO dependency management>
  * Click Finish

* Create your Liberty runtime environment
  * Window -> Show View -> Other -> Server -> Runtime Explorer
  * Right click in the Runtime Explorer view -> New -> Runtime Environment
  * Either choose an existing installation or install a from an archive or repository and then click Finish

* Create and configure your Liberty server
  * Windows -> Show View -> Other -> Servers -> Servers
  * Right-click in the Servers view -> New -> Server
  * Select WebSphere Application Server Liberty Profile
  * Ensure that your runtime environment is selected and click Next
  * On the next page, either select an existing server or create a new one
  * Don't add the lars server application to the server (yet).
  * Click Finish
  * Ensure that your new Liberty server is a targeted runtime for the server project:
    * Right click the *server* project -> Properties -> Targeted Runtime -> Select your new Liberty server and click OK
* Customise your server config for LARS
  * Expand your new server to find server.xml
  * Delete the contents of server.xml and replace them with the contents of tool.lars/server/config/server.xml
  * If you want to enable security:
    * Uncomment the <basicRegistry> element in server.xml (or create your own user registry). You will ned to set appropriate password(s) for any users that you define.
    * Uncomment the <application-bnd> element and customixe it to set user-role mappings.

* Add the LARS server application to your Liberty server
  * Servers view -> Right click your server -> Add and Remove...
  * In the list of Available applications on the left, select the *server* application
  * Click Add and verify that the *server* application now appears in the Configured list on the right
  * Click Finish
  * Edit the server.xml for your server:
    * Find the <webApplication> element and edit the location attribute to read location="server.war"
    * Save server.xml

* Start your LARS server
  * In the Servers view, right click your server and then click Start

Assuming all of this worked, you should be able to point a web browser to http://localhost:9080/ma/v1/assets to verify that the application is running. Any changes that you make to the source code in the server project should be automatically picked up and pushed to the application running on the server.
