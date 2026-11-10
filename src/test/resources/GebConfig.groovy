import org.openqa.selenium.Dimension
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.firefox.FirefoxOptions

waiting {
    timeout = 10
    retryInterval = 0.5
}

driver = {
    FirefoxOptions options = new FirefoxOptions()
    options.addArguments("-headless")
    options.addArguments("--width=1920")
    options.addArguments("--height=1080")
    FirefoxDriver driver = new FirefoxDriver(options)
    driver.manage().window().setSize(new Dimension(1920, 1080))
    driver
}
