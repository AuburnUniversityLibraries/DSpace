/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.servicemanager.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


/**
 * Testing the config service
 *
 * @author Aaron Zeckoski (azeckoski @ gmail.com)
 */
public class DSpaceConfigurationServiceTest {

    DSpaceConfigurationService configurationService;
    int numPropsLoaded;

    // Path to our main test config file (local.properties)
    private String propertyFilePath;

    @Before
    public void init() {
        configurationService = new DSpaceConfigurationService();

        // Save the path to our main test configuration file
        propertyFilePath = configurationService.getDSpaceHome(null) + File.separatorChar
            + DSpaceConfigurationService.DEFAULT_CONFIG_DIR + File.separatorChar + "local.properties";

        // clear out default configs (leaves us with an empty Configuration)
        configurationService.clear();

        // Start fresh with out own set of configs
        Map<String, Object> l = new HashMap<>();
        l.put("service.name", "DSpace");
        l.put("sample.array", "itemA,itemB,itemC");
        l.put("sample.number", "123");
        l.put("sample.boolean", "true");
        // 3 Billion cannot be stored as an "int" (max value 2^31-1)
        l.put("sample.long", "3000000000");
        l.put("aaronz", "Aaron Zeckoski");
        l.put("current.user", "${aaronz}");
        l.put("test.key1", "This is a value");
        l.put("test.key2", "This is key1=${test.key1}");
        // Hierarchical properties
        l.put("hier.key1.foo", "key1_foo");
        l.put("hier.key1.bar", "key1_bar");
        l.put("hier.key2.foo", "key2_foo");
        l.put("hier.key2.bar", "key2_bar");

        // Record how many properties we initialized with (for below unit tests)
        numPropsLoaded = 13;

        configurationService.loadConfiguration(l);
        l = null;
    }

    @After
    public void tearDown() {
        configurationService = null;
    }

    /**
     * A generic method to test that variable replacement is happening properly.
     */
    @Test
    public void testVariableReplacement() {

        Map<String, Object> l = new HashMap<>();
        l.put("service.name", "DSpace");
        l.put("aaronz", "Aaron Zeckoski");
        l.put("current.user", "${aaronz}");
        l.put("test.key1", "This is a value");
        l.put("test.key2", "This is key1=${test.key1}");
        l.put("test.key3", "This is key2=${test.key2}");

        configurationService.loadConfiguration(l);

        assertEquals("DSpace", configurationService.getProperty("service.name"));
        assertEquals("Aaron Zeckoski", configurationService.getProperty("aaronz"));
        assertEquals("Aaron Zeckoski", configurationService.getProperty("current.user"));
        assertEquals("This is a value", configurationService.getProperty("test.key1"));
        assertEquals("This is key1=This is a value", configurationService.getProperty("test.key2"));
        assertEquals("This is key2=This is key1=This is a value", configurationService.getProperty("test.key3"));

        //trash the references
        l = null;
    }

    @Test(expected = IllegalStateException.class)
    public void testVariableReplacementCircular() {
        // add a circular reference
        configurationService.loadConfig("circular", "${circular}");

        // try to get the value (should throw an error)
        configurationService.getProperty("circular");
    }

    @Test(expected = IllegalStateException.class)
    public void testVariableReplacementIndirectCircular() {
        // add a circular reference
        configurationService.loadConfig("circular", "${circular}");
        // add an indirect reference to that circular reference
        configurationService.loadConfig("indirect.circular", "$indirect ${circular}");

        // try to get the value (should throw an error)
        configurationService.getProperty("indirect.circular");
    }

    /**
     * Test method for {@link org.dspace.servicemanager.config.DSpaceConfigurationService#getProperties()}.
     */
    @Test
    public void testGetProperties() {
        Properties props = configurationService.getProperties();
        assertNotNull(props);
        assertEquals(numPropsLoaded, props.size());
        assertNotNull(props.get("service.name"));
        assertEquals("DSpace", props.get("service.name"));

        //trash the references
        props = null;
    }

    /**
     * Test method for
     * {@link org.dspace.servicemanager.config.DSpaceConfigurationService#getProperty(java.lang.String)}.
     */
    @Test
    public void testGetProperty() {
        String prop = configurationService.getProperty("service.name");
        assertNotNull(prop);
        assertEquals("DSpace", prop);

        prop = configurationService.getProperty("XXXXX");
        assertNull(prop);
        prop = null;
    }

    /**
     * Test method for
     * {@link org.dspace.servicemanager.config.DSpaceConfigurationService#getArrayProperty(java.lang.String)}.
     */
    @Test
    public void testGetArrayProperty() {
        String[] array = configurationService.getArrayProperty("sample.array");
        assertNotNull(array);
        assertEquals(3, array.length);
        assertEquals("itemA", array[0]);
        assertEquals("itemB", array[1]);
        assertEquals("itemC", array[2]);

        // Pass in default value
        array = configurationService.getArrayProperty("sample.array", new String[] {"Hey"});
        // Assert default value not used, since property exists
        assertEquals(3, array.length);

        array = configurationService.getArrayProperty("XXXXX");
        assertEquals(0, array.length);

        // Test default value
        array = configurationService.getArrayProperty("XXXXX", new String[] {"Hey"});
        assertEquals(1, array.length);
        assertEquals("Hey", array[0]);

        // Test escaping commas (with \,)
        configurationService.loadConfig("new.array", "A\\,B\\,C");
        array = configurationService.getArrayProperty("new.array");
        assertEquals(1, array.length);
        assertEquals("A,B,C", array[0]);
        configurationService.clearConfig("new.array");

        // Test multiline arrays (requires loading configs from local.properties test config file)
        // Specifying the same property multiple times should create an array of values.
        DSpaceConfigurationService dscs = new DSpaceConfigurationService();
        array = dscs.getArrayProperty("prop.multiline.array");
        assertNotNull(array);
        assertEquals(3, array.length);
        assertEquals("line1", array[0]);
        assertEquals("line2", array[1]);
        assertEquals("line3", array[2]);
        dscs.clear();
    }

    /**
     * Test method for
     * {@link org.dspace.servicemanager.config.DSpaceConfigurationService#getBooleanProperty(java.lang.String)}.
     */
    @Test
    public void testGetBooleanProperty() {
        boolean b = configurationService.getBooleanProperty("sample.boolean");
        assertEquals(true, b);

        // Pass in default value
        b = configurationService.getBooleanProperty("sample.boolean", false);
        assertEquals(true, b);

        b = configurationService.getBooleanProperty("XXXXX");
        assertEquals(false, b);

        // Pass in default value
        b = configurationService.getBooleanProperty("XXXXX", true);
        assertEquals(true, b);
    }

    /**
     * Test method for
     * {@link org.dspace.servicemanager.config.DSpaceConfigurationService#getIntProperty(java.lang.String)}.
     */
    @Test
    public void testGetIntProperty() {
        int i = configurationService.getIntProperty("sample.number");
        assertEquals(123, i);

        // Pass in default value
        i = configurationService.getIntProperty("sample.number", -1);
        assertEquals(123, i);

        i = configurationService.getIntProperty("XXXXX");
        assertEquals(0, i);

        // Pass in default value
        i = configurationService.getIntProperty("XXXXX", 345);
        assertEquals(345, i);
    }

    /**
     * Test method for
     * {@link org.dspace.servicemanager.config.DSpaceConfigurationService#getLongProperty(java.lang.String)}.
     */
    @Test
    public void testGetLongProperty() {
        long l = configurationService.getLongProperty("sample.long");
        //NOTE: "L" suffix ensures number is treated as a long
        assertEquals(3000000000L, l);

        // Pass in default value
        l = configurationService.getLongProperty("sample.long", -1);
        assertEquals(3000000000L, l);

        l = configurationService.getLongProperty("XXXXX");
        assertEquals(0, l);

        // Pass in default value
        l = configurationService.getLongProperty("XXXXX", 3000000001L);
        assertEquals(3000000001L, l);
    }

    /**
     * Test method for
     * {@link org.dspace.servicemanager.config.DSpaceConfigurationService#hasProperty(java.lang.String)}.
     */
    @Test
    public void testHasProperty() {
        assertEquals(true, configurationService.hasProperty("sample.array"));
        assertEquals(true, configurationService.hasProperty("sample.number"));
        assertEquals(false, configurationService.hasProperty("XXXXX"));
        assertEquals(false, configurationService.hasProperty("samplearray"));
    }

    /**
     * Test method for
     * {@link org.dspace.servicemanager.config.DSpaceConfigurationService#getPropertyAsType(java.lang.String, java.lang.Class)}.
     */
    @Test
    public void testGetPropertyAsTypeStringClassOfT() {
        String prop = configurationService.getPropertyAsType("service.name", String.class);
        assertNotNull(prop);
        assertEquals("DSpace", prop);

        String[] array = configurationService.getPropertyAsType("sample.array", String[].class);
        assertNotNull(array);
        assertEquals("itemA", array[0]);
        assertEquals("itemB", array[1]);
        assertEquals("itemC", array[2]);
        Integer number = configurationService.getPropertyAsType("sample.number", Integer.class);
        assertNotNull(number);
        assertEquals(Integer.valueOf(123), number);

        Boolean bool = configurationService.getPropertyAsType("sample.boolean", Boolean.class);
        assertNotNull(bool);
        assertEquals(Boolean.TRUE, bool);

        Boolean bool2 = configurationService.getPropertyAsType("INVALID.PROPERTY", Boolean.class);
        assertNotNull(bool2);
        assertEquals(Boolean.FALSE, bool2);

        boolean bool3 = configurationService.getPropertyAsType("INVALID.PROPERTY", boolean.class);
        assertEquals(false, bool3);

        assertEquals(123, (int) configurationService.getPropertyAsType("sample.number", int.class));
        assertEquals(true, (boolean) configurationService.getPropertyAsType("sample.boolean", boolean.class));

        prop = configurationService.getPropertyAsType("XXXXX", String.class);
        assertNull(prop);
        prop = null;
    }

    /**
     * Test method for
     * {@link org.dspace.servicemanager.config.DSpaceConfigurationService#getPropertyAsType(java.lang.String, java.lang.Object)}.
     */
    @Test
    public void testGetPropertyAsTypeStringT() {
        String prop = configurationService.getPropertyAsType("service.name", "DeeSpace");
        assertNotNull(prop);
        assertEquals("DSpace", prop);

        String[] array = configurationService.getPropertyAsType("sample.array", new String[] {"A", "B"});
        assertNotNull(array);
        assertEquals("itemA", array[0]);
        assertEquals("itemB", array[1]);
        assertEquals("itemC", array[2]);

        Integer number = configurationService.getPropertyAsType("sample.number", 12345);
        assertNotNull(number);
        assertEquals(Integer.valueOf(123), number);

        Boolean bool = configurationService.getPropertyAsType("sample.boolean", Boolean.FALSE);
        assertNotNull(bool);
        assertEquals(Boolean.TRUE, bool);

        boolean b = configurationService.getPropertyAsType("sample.boolean", false);
        assertTrue(b);

        prop = configurationService.getPropertyAsType("XXXXX", "XXX");
        assertEquals("XXX", prop);
        prop = null;
    }

    @Test
    public void testGetPropertyAsTypeStringTBoolean() {
        Object prop = configurationService.getPropertyValue("service.fake.thing");
        assertNull(prop);

        prop = configurationService.getPropertyAsType("service.fake.thing", "Fakey", false);
        assertNotNull(prop);
        assertEquals("Fakey", prop);

        prop = configurationService.getPropertyValue("service.fake.thing");
        assertNull(prop);

        prop = configurationService.getPropertyAsType("service.fake.thing", "Fakey", true);
        assertNotNull(prop);
        assertEquals("Fakey", prop);

        prop = configurationService.getPropertyValue("service.fake.thing");
        assertNotNull(prop);
        assertEquals("Fakey", prop);
        prop = null;
    }

    @Test
    public void testSetProperty() {

        // TEST setting a new Integer & retrieving using various methods
        Object prop = configurationService.getPropertyValue("newOne");
        assertNull(prop);

        boolean changed = configurationService.setProperty("newOne", "1111111");
        assertTrue(changed);

        prop = configurationService.getPropertyValue("newOne");
        assertNotNull(prop);
        assertEquals("1111111", prop);

        int i = configurationService.getIntProperty("newOne");
        assertEquals(1111111, i);

        // Test Setting a new Boolean and retrieving through various methods
        prop = configurationService.getPropertyValue("newBool");
        assertNull(prop);

        changed = configurationService.setProperty("newBool", true);
        assertTrue(changed);

        prop = configurationService.getPropertyValue("newBool");
        assertNotNull(prop);
        assertEquals(Boolean.TRUE, prop);

        boolean b = configurationService.getBooleanProperty("newBool");
        assertEquals(true, b);

        changed = configurationService.setProperty("newBool", true);
        assertFalse(changed);

        changed = configurationService.setProperty("newBool", null);
        assertTrue(changed);

        prop = configurationService.getPropertyValue("newBool");
        assertNull(prop);

        // Test Setting a new String and retrieving through various methods
        prop = configurationService.getPropertyValue("newString");
        assertNull(prop);

        changed = configurationService.setProperty("newString", "  Hi There      ");
        assertTrue(changed);

        // Assert strings are trimmed
        String s = configurationService.getProperty("newString");
        assertNotNull(s);
        assertEquals("Hi There", s);

        // Clear out our new props
        configurationService.clearConfig("newOne");
        configurationService.clearConfig("newBool");
        configurationService.clearConfig("newString");
        prop = null;

    }

    /**
     * Test method for {@link org.dspace.servicemanager.config.DSpaceConfigurationService#getConfiguration()}.
     */
    @Test
    public void testGetConfiguration() {
        assertNotNull(configurationService.getConfiguration());
        assertEquals(numPropsLoaded, configurationService.getProperties().size());
    }

    /**
     * Test method for {@link org.dspace.servicemanager.config.DSpaceConfigurationService#getHierarchicalConfiguration()}.
     */
    @Test
    public void testGetHierarchicalConfiguration() {
        HierarchicalConfiguration<ImmutableNode> config = configurationService.getHierarchicalConfiguration();

        assertNotNull(config);
        assertEquals(2, config.childConfigurationsAt("hier").size());
    }

    /**
     * Test method for {@link org.dspace.servicemanager.config.DSpaceConfigurationService#getChildren()}.
     */
    @Test
    public void testGetChildren() {
        List<HierarchicalConfiguration<ImmutableNode>> children = configurationService.getChildren("hier");

        assertNotNull(children);
        assertEquals(2, children.size());

        List<String> childPropertyNames = children.stream()
            .map(node -> node.getRootElementName())
            .collect(Collectors.toList());

        assertEquals(2, childPropertyNames.size());
        assertEquals("key1", childPropertyNames.get(0));
        assertEquals("key2", childPropertyNames.get(1));
    }

    /**
     * Test method for {@link org.dspace.servicemanager.config.DSpaceConfigurationService#getChildren()}.
     */
    @Test
    public void testGetChildrenNonExistentKey() {
        List<HierarchicalConfiguration<ImmutableNode>> children =
            configurationService.getChildren("thisKeyDoesNotExist");

        assertNotNull(children);
        assertEquals(0, children.size());
    }

    /**
     * Test method for {@link org.dspace.servicemanager.config.DSpaceConfigurationService#getChildren()}.
     */
    @Test
    public void testGetChildrenDeepKey() {
        List<HierarchicalConfiguration<ImmutableNode>> children = configurationService.getChildren("hier.key1");

        assertNotNull(children);
        assertEquals(2, children.size());

        List<String> childPropertyNames = children.stream()
            .map(node -> node.getRootElementName())
            .collect(Collectors.toList());

        assertEquals(2, childPropertyNames.size());
        assertEquals("foo", childPropertyNames.get(0));
        assertEquals("bar", childPropertyNames.get(1));
    }

    /**
     * Test method for
     * {@link org.dspace.servicemanager.config.DSpaceConfigurationService#loadConfig(java.lang.String, java.lang.Object)}.
     */
    @Test
    public void testLoadConfig() {
        assertEquals(numPropsLoaded, configurationService.getProperties().size());
        configurationService.loadConfig("newA", "A");
        assertEquals(numPropsLoaded + 1, configurationService.getProperties().size());
        assertEquals("A", configurationService.getProperty("newA"));
        configurationService.loadConfig("newB", "service is ${service.name}");
        assertEquals(numPropsLoaded + 2, configurationService.getProperties().size());
        assertEquals("service is DSpace", configurationService.getProperty("newB"));

        configurationService.loadConfig("newA", "aaronz");
        assertEquals(numPropsLoaded + 2, configurationService.getProperties().size());
        assertEquals("aaronz", configurationService.getProperty("newA"));

        // Clear out newly added props
        configurationService.clearConfig("newA");
        configurationService.clearConfig("newB");
        assertEquals(numPropsLoaded, configurationService.getProperties().size());
    }

    /**
     * Test method for {@link org.dspace.servicemanager.config.DSpaceConfigurationService#clear()}.
     */
    @Test
    public void testClear() {
        configurationService.clear();
        assertEquals(0, configurationService.getProperties().size());
    }

    /**
     * Test method for {@link org.dspace.servicemanager.config.DSpaceConfigurationService#reloadConfig()}.
     */
    @Test
    public void testReloadConfig() {
        // Initialize new config service
        DSpaceConfigurationService dscs = new DSpaceConfigurationService();
        int size = dscs.getProperties().size();

        // Add two new System properties
        System.setProperty("Hello", "World");
        System.setProperty("Tim", "Donohue");

        // Assert the new properties are not yet loaded
        assertEquals(size, dscs.getProperties().size());

        dscs.reloadConfig();

        // Assert the new properties now exist
        assertEquals(size + 2, dscs.getProperties().size());

        // Set a new value
        System.setProperty("Hello", "There");

        // Assert old value still in Configuration
        assertEquals("World", dscs.getProperty("Hello"));

        dscs.reloadConfig();

        // Now, should be new value
        assertEquals("There", dscs.getProperty("Hello"));

        // Clear set properties
        System.clearProperty("Hello");
        System.clearProperty("Tim");

        // Assert value not yet cleared from Configuration
        assertEquals("There", dscs.getProperty("Hello"));

        dscs.reloadConfig();

        // Now, should be null
        assertNull(dscs.getProperty("Hello"));

        dscs.clear();
        dscs = null;
    }

    /**
     * Tests the ability of our ConfigurationService to automatically reload
     * properties after a set period of time.
     * @throws ConfigurationException passed through.
     * @throws IOException if test properties file cannot be created or copied.
     * @throws InterruptedException if sleep is interrupted.
     */
    @Test
    public void testAutomaticReload() throws ConfigurationException, IOException, InterruptedException {
        // Initialize new config service
        DSpaceConfigurationService dscs = new DSpaceConfigurationService();

        // Assert a property exists with a specific initial value
        assertNotNull(dscs.getProperty("prop.to.auto.reload"));
        assertEquals("D-space", dscs.getProperty("prop.to.auto.reload"));

        // Copy our test local.properties file to a temp location (so we can restore it after tests below)
        File tempPropFile = File.createTempFile("temp", "properties");
        FileUtils.copyFile(new File(propertyFilePath), tempPropFile);

        // Now, change the value of that Property in the file itself (using a separate builder instance)
        FileBasedConfigurationBuilder<PropertiesConfiguration> builder = new Configurations()
            .propertiesBuilder(propertyFilePath);
        PropertiesConfiguration config = builder.getConfiguration();
        // Clear out current value. Add in a new value
        config.clearProperty("prop.to.auto.reload");
        config.addProperty("prop.to.auto.reload", "DSpace");
        // Save updates to file (this changes our test local.properties)
        builder.save();

        // Check immediately. Property should be unchanged
        // NOTE: If this fails, then somehow the configuration reloaded *immediately*
        assertEquals("D-space", dscs.getProperty("prop.to.auto.reload"));

        // Wait now for 3 seconds
        Thread.sleep(3_000);

        // Check again. Property should have reloaded
        // NOTE: reload time is set in config-definition.xml to reload every 2 seconds
        assertEquals("DSpace", dscs.getProperty("prop.to.auto.reload"));

        // Restore our test local.properties file to original content
        FileUtils.copyFile(tempPropFile, new File(propertyFilePath));
    }

    /**
     * Tests the ability of the system to properly extract system properties into the configuration.
     * (NOTE: This ability to load system properties is specified in the test "config-definition.xml")
     */
    @Test
    public void testGetPropertiesFromSystem() {
        DSpaceConfigurationService dscs = new DSpaceConfigurationService();
        int size = dscs.getProperties().size();

        System.setProperty("dspace.system.config", "Hello");
        System.setProperty("another.property", "Adios");

        dscs.reloadConfig();

        assertEquals(size + 2, dscs.getProperties().size());
        assertEquals("Hello", dscs.getProperty("dspace.system.config"));
        assertEquals("Adios", dscs.getProperty("another.property"));

        System.clearProperty("dspace.system.config");
        System.clearProperty("another.property");
        dscs.clear();
        dscs = null;
    }

    /**
     * Tests the ability of the system to properly extract properties from files
     * (NOTE: The local.properties test file is specified in the test "config-definition.xml")
     */
    @Test
    public void testGetPropertiesFromFile() {

        DSpaceConfigurationService dscs = new DSpaceConfigurationService();

        // Test that property values are automatically trimmed of leading/trailing spaces
        // In local.properties, this value is something like "   test    "
        assertEquals("test", dscs.getProperty("prop.needing.trimmed"));

        // Also test that properties in included files are loaded
        // This property is specified in "included.properties", which is loaded via an "include =" statement in
        // local.properties
        assertEquals("works", dscs.getProperty("prop.from.included.file"));

        dscs.clear();
        dscs = null;
    }

    /**
     * Test method for
     * {@link org.dspace.servicemanager.config.DSpaceConfigurationService#getDSpaceHome(java.lang.String)}.
     */
    @Test
    public void testGetDSpaceHomeSysProperty() {
        // Capture current value of DSPACE_HOME (so we can reset it after test)
        String previousValue = System.getProperty(DSpaceConfigurationService.DSPACE_HOME);
        // Change to a mocked value
        System.setProperty(DSpaceConfigurationService.DSPACE_HOME, "/mydspace");

        // Create a spy of our loaded configurationService, and tell it to return true
        // when "isValidDSpaceHome()" is called with "/mydspace"
        DSpaceConfigurationService spy = spy(configurationService);
        when(spy.isValidDSpaceHome("/mydspace")).thenReturn(true);

        // Assert Home is the same as System Property
        assertEquals("System property set", "/mydspace", spy.getDSpaceHome(null));

        // reset DSPACE_HOME to previous value
        System.setProperty(DSpaceConfigurationService.DSPACE_HOME, previousValue);
    }

    @Test
    public void testGetDSpaceHomeSysPropertyOverride() {
        // Capture current value of DSPACE_HOME (so we can reset it after test)
        String previousValue = System.getProperty(DSpaceConfigurationService.DSPACE_HOME);
        // Change to a mocked value
        System.setProperty(DSpaceConfigurationService.DSPACE_HOME, "/mydspace");

        // Create a spy of our loaded configurationService, and tell it to return true
        // when "isValidDSpaceHome()" is called with "/mydspace"
        DSpaceConfigurationService spy = spy(configurationService);
        when(spy.isValidDSpaceHome("/mydspace")).thenReturn(true);

        // Assert System Property overrides the value passed in, if it is valid
        assertEquals("System property override", "/mydspace", spy.getDSpaceHome("/myotherdspace"));

        // reset DSPACE_HOME to previous value
        System.setProperty(DSpaceConfigurationService.DSPACE_HOME, previousValue);
    }

    @Test
    public void testGetDSpaceHomeNoSysProperty() {
        // Capture current value of DSPACE_HOME (so we can reset it after test)
        String previousValue = System.getProperty(DSpaceConfigurationService.DSPACE_HOME);
        // Clear the value
        System.clearProperty(DSpaceConfigurationService.DSPACE_HOME);

        // Create a spy of our loaded configurationService, and tell it to return true
        // when "isValidDSpaceHome()" is called with "/mydspace"
        DSpaceConfigurationService spy = spy(configurationService);
        when(spy.isValidDSpaceHome("/mydspace")).thenReturn(true);

        // Assert provided home is used
        assertEquals("Home based on passed in value", "/mydspace", spy.getDSpaceHome("/mydspace"));

        // reset DSPACE_HOME to previous value
        System.setProperty(DSpaceConfigurationService.DSPACE_HOME, previousValue);
    }
}
