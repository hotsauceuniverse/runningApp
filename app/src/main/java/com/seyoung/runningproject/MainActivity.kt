package com.seyoung.runningproject

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNavigation: BottomNavigationView

    // Fragment를 한 번만 생성
    private val homeFragment = FragmentHome()
    private val runningFragment = FragmentRunning()
    private val myPageFragment = FragmentMyPage()

    private var currentFragment: Fragment = homeFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bottomNavigation = findViewById(R.id.bottom_navigation)

        // =========================
        // Fragment 최초 생성
        // =========================

        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, homeFragment, "HOME")
            .add(R.id.fragment_container, runningFragment, "RUNNING")
            .hide(runningFragment)
            .add(R.id.fragment_container, myPageFragment, "MYPAGE")
            .hide(myPageFragment)
            .commit()

        currentFragment = homeFragment

        // =========================
        // BottomNavigation 클릭
        // =========================
        bottomNavigation.setOnItemSelectedListener { item ->

            val selectedFragment = when (item.itemId) {
                R.id.menu_home -> homeFragment
                R.id.menu_running -> runningFragment
                R.id.menu_mypage -> myPageFragment
                else -> return@setOnItemSelectedListener false
            }

            // 이미 현재 화면이면 아무것도 안 함
            if (selectedFragment == currentFragment) {
                return@setOnItemSelectedListener true
            }

            // 현재 Fragment 숨기고 선택한 Fragment 보여주기
            supportFragmentManager.beginTransaction()
                .hide(currentFragment)
                .show(selectedFragment)
                .commit()

            currentFragment = selectedFragment

            true
        }
    }
}