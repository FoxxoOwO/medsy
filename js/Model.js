class Model {
    #meds = [];
    

    constructor() {
        this.#meds = this.#loadFromStorage();
    }


    getMedications() {
        return this.#meds;
    }

    getById(id) {
        return this.#meds.find(m => m.id === id);
    }

    addOrUpdate(name, dosage, times, id) {
        if (id) {
            const index = this.#meds.findIndex(m => m.id === id);
            if(index !== -1) {
                const oldMed = this.#meds[index];
                this.#meds[index] = new Medication(name, dosage, times, id, oldMed.history);
            }
        } else {
            const newMed = new Medication(name, dosage, times);
            this.#meds.push(newMed);
        }
        
        
    }

    remove(id) {
        this.#meds = this.#meds.filter(m => m.id !== id);
        
    }


    #saveToStorage(meds) {
        localStorage.setItem('medsy_db', JSON.stringify(meds));
    }

    #loadFromStorage() {
        const data = JSON.parse(localStorage.getItem('medsy_db') || '[]');
        return data.map(d => new Medication(d.name, d.dosage, d.times, d.id, d.history));
    }
}


class Medication {
    constructor(name, dosage, times, id = null, history = []) {
        this.id = id || crypto.randomUUID();
        this.name = name;
        this.dosage = dosage;
        this.times = times; 
        this.history = history;
    }
}