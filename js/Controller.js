class Controller {
    #model;
    #view;
    #activePage = 'page-today';

    constructor(model, view) {
        this.#model = model;
        this.#view = view;

        this.#view.bindNavigation(this.handleNavigation);

    }


    onMedListChanged = (meds) => {
        this.#view.render(meds, this.#activePage);
    }

    handleNavigation = (pageId) => {
        this.#activePage = pageId;
        this.#view.render(this.#model.getMedications(), pageId);
    }
}