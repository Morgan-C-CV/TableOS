package com.tableos.beakerlab

enum class ChemicalType {
    Na,
    H2O,
    HCl,
    NaOH,
    Cl2,
    O2,
    H2,
    CO2
}

data class ChemicalCard(
    val id: Int,
    val type: ChemicalType,
    var x: Float, // center x in px
    var y: Float, // center y in px
    var radius: Float // display radius in px
)