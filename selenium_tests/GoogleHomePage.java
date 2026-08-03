// Page Object Model (POM) classes

public class GoogleHomePage extends GooglePage {
    @FindBy(linkText = "Google")
    private WebElement googleLogo;

    public boolean isGoogleLogoDisplayed() {
        return googleLogo.isDisplayed();
    }
}