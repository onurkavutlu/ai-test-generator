// Page Object Model (POM) classes

public class GooglePage {
    @FindBy(name = "q")
    private WebElement searchBox;

    @FindBy(id = "gsr")
    private WebElement searchResult;

    public void setSearchBoxValue(String value) {
        searchBox.sendKeys(value);
    }

    public String getSearchResultText() {
        return searchResult.getText();
    }
}